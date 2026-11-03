// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.auris.ai.dubbing.entity.DubbingTaskDO;
import com.mars.auris.ai.dubbing.enums.DubbingKind;
import com.mars.auris.ai.dubbing.enums.DubbingTaskStatus;
import com.mars.auris.ai.dubbing.mapper.DubbingTaskMapper;
import com.mars.auris.ai.config.StorageProperties;
import com.mars.auris.ai.dubbing.model.response.AssetUrlResp;
import com.mars.auris.ai.dubbing.model.response.DubbingTaskResp;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dubbing task orchestration (D6/D8): submit idempotency (uk_user_url_hash unique-key fallback) + poll-to-DB
 * + asset streaming proxy and presigned url issuing. Two-level polling, each manages its own: getTask only calls
 * engine once for non-terminal tasks and persists progress; list is pure DB read. MySQL is the authoritative
 * state; engine unreachable (404) → local mark-failed, no zombie rows.
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
    private S3Presigner s3Presigner;

    @Autowired
    private StorageProperties storageProperties;

    public SubmitTaskResp submitUrl(Long userId, String url) {
        // Insert first (INIT) then call engine: the task lifecycle starts at "user submission"; engine-accept failures also leave a trace.
        // Oversized url is rejected at the entry by @Size(max=1024); no truncation here so the hash shares the same source as the stored value.
        DubbingKind kind = guessKind(url);
        DubbingTaskDO task = new DubbingTaskDO();
        task.setUserId(userId);
        task.setUrl(url);
        task.setUrlHash(DigestUtils.md5DigestAsHex(url.getBytes(StandardCharsets.UTF_8)));
        task.setKind(kind.getCode());
        task.setStatus(DubbingTaskStatus.INIT.getCode());
        task.setStageIndex(0);
        task.setStageTotal(kind.getStageTotal());
        try {
            // Idempotency by unique-key fallback, no check-then-insert (concurrency window): one row per user+url for life
            mapper.insert(task);
        } catch (DuplicateKeyException e) {
            // uk_user_url_hash hit -> query back and reuse the existing row (in-flight = idempotent return; terminal = reuse, no resubmit)
            DubbingTaskDO existed = mapper.selectOne(Wrappers.<DubbingTaskDO>lambdaQuery()
                    .eq(DubbingTaskDO::getUserId, userId)
                    .eq(DubbingTaskDO::getUrlHash, task.getUrlHash())
                    .last("LIMIT 1"));
            if (existed == null) {
                // key hit but row missing = out-of-model anomaly (same policy as TranscribeRecordService)
                throw new AurisException(CommonErrorCode.INTERNAL_ERROR);
            }
            return new SubmitTaskResp(String.valueOf(existed.getId()));
        }
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
            task.setStatus(DubbingTaskStatus.FAILED.getCode());
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
        if (DubbingTaskStatus.isTerminal(task.getStatus())) {
            return to(task);   // terminal states served from DB, no engine call
        }
        if (task.getStatus() == DubbingTaskStatus.INIT.getCode() && task.getEngineTaskId() == null) {
            // Orphan from an interrupted submit (process crash after the engine call etc.): backfill terminal state, better than forever-INIT
            task.setStatus(DubbingTaskStatus.FAILED.getCode());
            task.setErrorMsg("Aborted at submission (engine did not accept)");
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
                task.setStatus(DubbingTaskStatus.FAILED.getCode());
                task.setErrorMsg("Engine task lost (expired or restarted)");
                mapper.updateById(task);
            }
            throw e;
        }
        DubTaskDTO d = result.getData();
        int st = d.getStatus() == null ? DubbingTaskStatus.RUNNING.getCode() : d.getStatus();   // engine oddity fallback: treat as running
        if (st == DubbingTaskStatus.COMPLETED.getCode()) {
            task.setStatus(DubbingTaskStatus.COMPLETED.getCode());
            task.setTitle(d.getTitle());
            task.setAudioBytes(d.getAudioBytes());
            task.setStageIndex(d.getStageTotal());
        } else if (st == DubbingTaskStatus.FAILED.getCode()) {
            task.setStatus(DubbingTaskStatus.FAILED.getCode());
            task.setErrorMsg(truncate(d.getError()));
        } else {
            task.setStatus(DubbingTaskStatus.RUNNING.getCode());   // INIT/RUNNING: mark running on first poll, advance stage
            task.setStageIndex(d.getStageIndex());
        }
        if (d.getTitle() != null && task.getTitle() == null) {
            task.setTitle(d.getTitle());   // title exists once download finishes, backfill early
        }
        if (d.getStageTotal() != null && d.getStageTotal() > 0) {
            task.setStageTotal(d.getStageTotal());   // stage_total defers to engine (corrects the coarse guess)
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
        if (task == null || task.getStatus() != DubbingTaskStatus.COMPLETED.getCode() || task.getEngineTaskId() == null) {
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


    /**
     * Presigned url issuing (D8): Java goes from "proxying bytes" to "issuing credentials";
     * the browser connects to object storage directly. Pure local computation (SigV4),
     * zero network calls; the key is derived by convention.
     * kind whitelist by task form: presign cannot verify object existence, and a fallback
     * would issue a URL that always 403s on open — surface the error at signing time (404),
     * not playback (deliberately different from streamAsset's fallback semantics).
     */
    public AssetUrlResp signAsset(Long userId, Long id, String kind) {
        DubbingTaskDO task = findByUserIdAndId(userId, id);
        if (task == null || task.getStatus() != DubbingTaskStatus.COMPLETED.getCode() || task.getEngineTaskId() == null) {
            throw new AurisException(AIErrorCode.TASK_NOT_FOUND);
        }

        // map kind to artifact name by task form: video tasks have output.mp4/dub.srt, audio only narration.mp3
        boolean video = DubbingKind.VIDEO.getCode().equals(task.getKind());
        String name = null;
        if (video) {
            if ("video".equals(kind)) {
                name = "output.mp4";
            } else if ("srt".equals(kind)) {
                name = "dub.srt";
            }
        } else if (kind == null || "audio".equals(kind)) {
            name = "narration.mp3";
        }
        if (name == null) {
            throw new AurisException(AIErrorCode.TASK_NOT_FOUND);   // form mismatch, 404 without leaking existence
        }

        String key = "dubbing/" + task.getEngineTaskId() + "/" + name;
        var presigned = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(storageProperties.getPresignExpire())
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(storageProperties.getBucket())
                        .key(key)
                        .build())
                .build());
        log.info("signAsset presigned: {}", presigned);
        return new AssetUrlResp(presigned.url().toString(),
                storageProperties.getPresignExpire().toSeconds());
    }

    private static DubbingKind guessKind(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("bilibili.com") || u.contains("b23.tv")) {
            return DubbingKind.VIDEO;
        }
        int dot = u.lastIndexOf('.');
        String ext = dot >= 0 ? u.substring(dot) : "";
        return VIDEO_EXT.contains(ext) ? DubbingKind.VIDEO : DubbingKind.AUDIO;
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
        r.setStatus(DubbingTaskStatus.of(t.getStatus()).name().toLowerCase());
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
