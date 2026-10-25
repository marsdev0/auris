// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.auris.ai.config.EngineProperties;
import com.mars.auris.ai.dubbing.entity.DubbingTaskDO;
import com.mars.auris.ai.dubbing.mapper.DubbingTaskMapper;
import com.mars.auris.ai.dubbing.model.DubbingTaskResp;
import com.mars.auris.ai.dubbing.model.engine.DubTaskDTO;
import com.mars.auris.ai.engine.EngineClient;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.model.PageResp;
import com.mars.auris.ai.transcribe.model.SubmitTaskResp;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dubbing task orchestration (D6): submit idempotency + poll-to-DB + asset streaming proxy.
 * Two-level polling, each manages its own: getTask only calls engine once for non-terminal tasks and persists progress;
 * list is pure DB read. MySQL is the authoritative state; engine unreachable (404) → local mark-failed, no zombie rows.
 */
@Slf4j
@Service
public class DubbingService {

    private static final String URL_DUB_SUBMIT = "/v1/dubbing/task";
    private static final String URL_DUB_TASK_GET = "/v1/dubbing/task/";

    /** For kind rough-guessing (same vocabulary as engine _guess_kind; authoritative value from engine response) */
    private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".mkv", ".webm", ".mov");

    @Autowired
    @Qualifier("engineSubmit")
    private RestClient engineSubmit;

    @Autowired
    @Qualifier("enginePoll")
    private RestClient enginePoll;

    @Autowired
    @Qualifier("engineAsset")
    private RestClient engineAsset;

    @Autowired
    private DubbingTaskMapper mapper;

    @Autowired
    private EngineClient engineClient;

    @Autowired
    private EngineProperties properties;

    public SubmitTaskResp submitUrl(Long userId, String url) {
        // Idempotency: same user + url with an in-flight task → reject duplicate submit, return the existing task
        DubbingTaskDO inflight = mapper.selectOne(Wrappers.<DubbingTaskDO>lambdaQuery()
                .eq(DubbingTaskDO::getUserId, userId)
                .eq(DubbingTaskDO::getUrl, url)
                .in(DubbingTaskDO::getStatus, 0, 1)
                .last("LIMIT 1"));
        if (inflight != null) {
            return new SubmitTaskResp(String.valueOf(inflight.getId()));
        }

        // Insert first (INIT) then call engine: the task lifecycle starts at "user submission"; engine-accept failures also leave a trace
        DubbingTaskDO task = new DubbingTaskDO();
        task.setUserId(userId);
        task.setUrl(url.length() > 1024 ? url.substring(0, 1024) : url);
        task.setKind(guessKind(url));
        task.setStatus(0);
        task.setStageIndex(0);
        task.setStageTotal("video".equals(task.getKind()) ? 8 : 6);
        mapper.insert(task);
        try {
            EngineResp<JsonNode> result = callEngine(() -> engineSubmit.post()
                    .uri(URL_DUB_SUBMIT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("url", url))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    }));
            task.setEngineTaskId(result.getData().get("task_id").asText());
        } catch (AurisException e) {
            task.setStatus(3);
            task.setErrorMsg(truncate(e.getMsg()));
            mapper.updateById(task);
            throw e;
        }
        mapper.updateById(task);
        return new SubmitTaskResp(String.valueOf(task.getId()));
    }

    public DubbingTaskResp getTask(Long userId, Long id) {
        DubbingTaskDO task = findByUserIdAndId(userId, id);
        if (task == null) {
            throw new AurisException(AIErrorCode.TASK_NOT_FOUND);
        }
        if (task.getStatus() >= 2) {
            return to(task);   // 终态回源 DB,不打 engine
        }
        if (task.getStatus() == 0 && task.getEngineTaskId() == null) {
            // Orphan from an interrupted submit (process crash after the engine call etc.): backfill terminal state, better than forever-INIT
            task.setStatus(3);
            task.setErrorMsg("发起中断(engine 未受理)");
            mapper.updateById(task);
            return to(task);
        }

        EngineResp<DubTaskDTO> result;
        try {
            result = callEngine(() -> enginePoll.get()
                    .uri(URL_DUB_TASK_GET + task.getEngineTaskId())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    }));
        } catch (AurisException e) {
            // Engine TTL expired / restarted and lost the in-memory task → mark failed locally; don't let it run forever
            if (Objects.equals(e.getCode(), AIErrorCode.TASK_NOT_FOUND.getCode())) {
                task.setStatus(3);
                task.setErrorMsg("engine 任务丢失(过期或重启)");
                mapper.updateById(task);
            }
            throw e;
        }
        DubTaskDTO d = result.getData();
        int st = d.getStatus() == null ? 1 : d.getStatus();   // 引擎异常形态兜底当 running
        if (st == 2) {
            task.setStatus(2);
            task.setTitle(d.getTitle());
            task.setAudioBytes(d.getAudioBytes());
            task.setStageIndex(d.getStageTotal());
        } else if (st == 3) {
            task.setStatus(3);
            task.setErrorMsg(truncate(d.getError()));
        } else {
            task.setStatus(1);   // INIT(0)/RUNNING(1) 轮询到即开工,推进度
            task.setStageIndex(d.getStageIndex());
        }
        if (d.getTitle() != null && task.getTitle() == null) {
            task.setTitle(d.getTitle());   // title 下载完就有,提前回填
        }
        if (d.getStageTotal() != null && d.getStageTotal() > 0) {
            task.setStageTotal(d.getStageTotal());   // stage_total 以 engine 为准(校准粗判)
        }
        if (d.getKind() != null && !d.getKind().isBlank()) {
            task.setKind(d.getKind());
        }
        mapper.updateById(task);
        return to(task);
    }

    public PageResp<DubbingTaskResp> list(Long userId, int page, int size) {
        long p = Math.max(page, 1);
        long s = size < 1 ? 10 : Math.min(size, 50);
        Page<DubbingTaskDO> result = mapper.selectPage(
                new Page<>(p, s),
                Wrappers.<DubbingTaskDO>lambdaQuery()
                        .eq(DubbingTaskDO::getUserId, userId)
                        .orderByDesc(DubbingTaskDO::getId));
        List<DubbingTaskResp> items = result.getRecords().stream().map(DubbingService::to).toList();
        return new PageResp<>(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }

    public void delete(Long userId, Long id) {
        if (mapper.delete(Wrappers.<DubbingTaskDO>lambdaQuery()
                .eq(DubbingTaskDO::getUserId, userId)
                .eq(DubbingTaskDO::getId, id)) == 0) {
            throw new AurisException(AIErrorCode.TASK_NOT_FOUND);
        }
        // Artifact cleanup is engine TTL's job (Java is unaware of engine files)
    }

    /**
     * Asset streaming proxy (D6): verify ownership + terminal state, then forward to engine, Range/Content-Type passthrough —
     * kind=audio(mp3)|video(mp4)|srt(subtitles); player progress-bar dragging relies on Range passthrough.
     * exchange handles status codes manually: non-2xx passes through the engine status; RestClient exceptions don't interrupt the stream.
     */
    public void streamAsset(Long userId, Long id, String kind, String range, HttpServletResponse resp) {
        DubbingTaskDO task = findByUserIdAndId(userId, id);
        if (task == null || task.getStatus() != 2 || task.getEngineTaskId() == null) {
            throw new AurisException(AIErrorCode.TASK_NOT_FOUND);
        }
        String safeKind = switch (kind == null ? "audio" : kind) {
            case "video", "srt" -> kind;
            default -> "audio";
        };
        engineAsset.get()
                .uri(URL_DUB_TASK_GET + task.getEngineTaskId() + "/asset?kind=" + safeKind)
                .headers(h -> {
                    if (range != null && !range.isBlank()) {
                        h.set("Range", range);
                    }
                })
                .exchange((req, ersp) -> {
                    resp.setStatus(ersp.getStatusCode().value());
                    resp.setContentType(Objects.requireNonNullElse(
                                    ersp.getHeaders().getContentType(), MediaType.parseMediaType("application/octet-stream"))
                            .toString());
                    passHeader(resp, "Content-Range", ersp.getHeaders().getFirst("Content-Range"));
                    passHeader(resp, "Content-Length", ersp.getHeaders().getFirst("Content-Length"));
                    resp.setHeader("Accept-Ranges", "bytes");
                    try (InputStream in = ersp.getBody()) {
                        in.transferTo(resp.getOutputStream());
                    }
                    return null;
                });
    }

    private static String guessKind(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("bilibili.com") || u.contains("b23.tv")) {
            return "video";
        }
        int dot = u.lastIndexOf('.');
        String ext = dot >= 0 ? u.substring(dot) : "";
        return VIDEO_EXT.contains(ext) ? "video" : "audio";
    }

    private static void passHeader(HttpServletResponse resp, String name, String value) {
        if (value != null) {
            resp.setHeader(name, value);
        }
    }

    private DubbingTaskDO findByUserIdAndId(Long userId, Long id) {
        return mapper.selectOne(Wrappers.<DubbingTaskDO>lambdaQuery()
                .eq(DubbingTaskDO::getUserId, userId)
                .eq(DubbingTaskDO::getId, id));
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 512 ? s.substring(0, 512) : s);
    }

    private static DubbingTaskResp to(DubbingTaskDO t) {
        DubbingTaskResp r = new DubbingTaskResp();
        r.setId(String.valueOf(t.getId()));
        r.setTitle(t.getTitle());
        r.setUrl(t.getUrl());
        r.setKind(t.getKind());
        r.setStatus(switch (t.getStatus()) {
            case 2 -> "completed";
            case 3 -> "failed";
            case 0 -> "init";
            default -> "running";
        });
        r.setStageIndex(t.getStageIndex());
        r.setStageTotal(t.getStageTotal());
        r.setAudioBytes(t.getAudioBytes());
        r.setError(t.getErrorMsg());
        r.setCreatedAt(t.getCreatedAt());
        return r;
    }

    // Unified engine call exit (same discipline as TranscribeService.callEngine)
    private <T> EngineResp<T> callEngine(Supplier<EngineResp<T>> call) {
        EngineResp<T> result = engineClient.execute(call, DubbingService::toAurisException);
        if (result == null || !result.isSuccess()) {
            throw new AurisException(AIErrorCode.ENGINE_ERROR);
        }
        return result;
    }

    private static AurisException toAurisException(RestClientResponseException e) {
        String detail = extractDetail(e);
        return switch (e.getStatusCode().value()) {
            case 400 -> new AurisException(CommonErrorCode.BAD_REQUEST, detail);
            case 404 -> new AurisException(AIErrorCode.TASK_NOT_FOUND, detail);
            default -> new AurisException(AIErrorCode.ENGINE_ERROR);
        };
    }

    private static String extractDetail(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
