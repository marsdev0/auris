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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 配音任务编排(D6):发起幂等(uk_user_url_hash 唯一键兜底) + 轮询落库 + asset 流式代理。
 * 两层轮询各管一层:本服务的 getTask 只对非终态任务回源 engine 一次并落库推进度;
 * list 纯读 DB。MySQL 是状态权威源,engine 失联(404)本地标失败,不留僵尸行。
 */
@Slf4j
@Service
public class DubbingService {

    private static final String URL_DUB_SUBMIT = "/v1/dubbing/task";
    private static final String URL_DUB_TASK_GET = "/v1/dubbing/task/";

    /** kind 粗判用(与 engine _guess_kind 同口径;精确判定以 engine 回包为准) */
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

    public SubmitTaskResp submitUrl(Long userId, String url) {
        // 先落库(INIT)再调 engine:任务生命周期从"用户发起"起算,engine 受理失败也留痕
        // url 超长已在入口 @Size(max=1024) 拒绝,此处不截断,hash 与落库值同源
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
            // 幂等靠唯一键兜底,不先查再插(有并发窗口):同 user 同 url 终身一行
            mapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 撞 uk_user_url_hash → 回查续用已有任务(在途=幂等返回;终态=同条任务复用,不新发)
            DubbingTaskDO existed = mapper.selectOne(Wrappers.<DubbingTaskDO>lambdaQuery()
                    .eq(DubbingTaskDO::getUserId, userId)
                    .eq(DubbingTaskDO::getUrlHash, task.getUrlHash())
                    .last("LIMIT 1"));
            if (existed == null) {
                // 撞键却查不到 = 超出模型的异常状态(同 TranscribeRecordService 口径)
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
            return to(task);   // 终态回源 DB,不打 engine
        }
        if (task.getStatus() == DubbingTaskStatus.INIT.getCode() && task.getEngineTaskId() == null) {
            // 发起中断的孤儿(engine 调用后进程崩溃等):补终态,比永远 INIT 强
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
            // engine TTL 过期/重启丢内存任务 → 本地标失败,别让任务永远 running
            if (Objects.equals(e.getCode(), AIErrorCode.TASK_NOT_FOUND.getCode())) {
                task.setStatus(DubbingTaskStatus.FAILED.getCode());
                task.setErrorMsg("Engine task lost (expired or restarted)");
                mapper.updateById(task);
            }
            throw e;
        }
        DubTaskDTO d = result.getData();
        int st = d.getStatus() == null ? DubbingTaskStatus.RUNNING.getCode() : d.getStatus();   // 引擎异常形态兜底当 running
        if (st == DubbingTaskStatus.COMPLETED.getCode()) {
            task.setStatus(DubbingTaskStatus.COMPLETED.getCode());
            task.setTitle(d.getTitle());
            task.setAudioBytes(d.getAudioBytes());
            task.setStageIndex(d.getStageTotal());
        } else if (st == DubbingTaskStatus.FAILED.getCode()) {
            task.setStatus(DubbingTaskStatus.FAILED.getCode());
            task.setErrorMsg(truncate(d.getError()));
        } else {
            task.setStatus(DubbingTaskStatus.RUNNING.getCode());   // INIT/RUNNING 轮询到即开工,推进度
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
        // 产物清理由 engine TTL 兜底(Java 不感知 engine 文件)
    }

    /**
     * asset 流式代理(D6):校验归属+终态后转发 engine,Range/Content-Type 透传——
     * kind=audio(mp3)|video(mp4)|srt(字幕),播放器拖进度条依赖 Range 透传。
     * exchange 手动处理状态码:非 2xx 透传引擎状态,不让 RestClient 抛异常中断流。
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

    // engine 调用统一出口(照 TranscribeService.callEngine 同款纪律)
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
