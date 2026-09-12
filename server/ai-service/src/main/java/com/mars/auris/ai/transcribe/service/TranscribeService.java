// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.service;

import com.mars.auris.ai.config.EngineProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.convert.TranscribeConvert;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import com.mars.auris.ai.transcribe.model.LongTaskResp;
import com.mars.auris.ai.transcribe.model.SubmitTaskResp;
import com.mars.auris.ai.transcribe.model.TranscribeResp;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
import com.mars.auris.ai.transcribe.model.engine.AsrTaskDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * @author geyan
 * @date 2026/8/28
 */
@Slf4j
@Service
public class TranscribeService {

    @Autowired
    @Qualifier("engineSubmit")
    private RestClient engineSubmit;

    @Autowired
    @Qualifier("enginePoll")
    private RestClient enginePoll;

    @Autowired
    private TranscribeConvert convert;

    @Autowired
    private EngineProperties properties;

    @Autowired
    private TranscribeRecordService recordService;

    /**
     * 同步返回
     */
    public TranscribeResp transcribeSync(Long userId, byte[] audio, String provider) {
        EngineResp<AsrResultDTO> result = callEngine(() -> engineSubmit.post()
                .uri(TranscribeConst.URL_ASR_TRANSCRIBE)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(getMultipartBodyBuilder(audio, provider).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        recordService.saveTranscribeSyncResult(userId, result);
        return convert.to(result.getData());
    }


    public SubmitTaskResp submitTask(Long userId, byte[] audio, String provider) {
        EngineResp<String> result = callEngine(() -> engineSubmit.post()
                .uri(TranscribeConst.URL_ASR_TASK_START)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(getMultipartBodyBuilder(audio, provider).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        Long recordId = recordService.createTask(userId, result.getData());
        return new SubmitTaskResp(String.valueOf(recordId));
    }


    public LongTaskResp getTask(Long userId, Long recordId) {
        // 1. 先查DB，如果是终态，则直接返回，同时比较userId; 如果是非终态，继续查engine
        TranscribeRecordDO record = recordService.findByUserIdAndRecordId(userId, recordId);
        if (record == null) {
            throw new AurisException(AIErrorCode.TASK_NOT_FOUND);
        }
        if (record.getStatus() != 0) {
            // 终态(completed/failed)直接回源 DB,不再打 engine
            return convert.to(record);
        }


        EngineResp<AsrTaskDTO> result = callEngine(() -> enginePoll.get()
                .uri(TranscribeConst.URL_ASR_TASK_GET + record.getEngineTaskId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        if (TranscribeConst.ENGINE_STATUS_COMPLETED.equals(result.getData().getStatus())) {
            recordService.complete(userId, record.getId(), result.getData());
        } else if (TranscribeConst.ENGINE_STATUS_FAILED.equals(result.getData().getStatus())) {
            recordService.fail(userId, record.getId(), result.getMessage());
        }

        LongTaskResp resp = convert.to(result.getData());
        resp.setRecordId(String.valueOf(recordId));
        return resp;
    }


    /**
     * engine 调用统一出口:非 0 业务码 + HTTP 异常在此映射为 AurisException。
     * 映射表(P1 方案 §3.4):
     *   400 空内容/解码失败 → AUDIO_INVALID(msg 透传 engine 原文)
     *   404 任务不存在/过期 → TASK_NOT_FOUND;413 → FILE_TOO_LARGE
     *   5xx → ENGINE_ERROR(不透传原文,可能含内部路径,日志留 detail)
     *   连不上/超时 → ENGINE_UNAVAILABLE
     */
    private <T> EngineResp<T> callEngine(EngineCall<T> call) {
        EngineResp<T> result;
        try {
            result = call.get();
        } catch (ResourceAccessException e) {
            // 连不上/超时(engine 停机、网络断):快速失败,503
            log.warn("engine 不可达: {}", e.getMessage());
            throw new AurisException(AIErrorCode.ENGINE_UNAVAILABLE);
        } catch (RestClientResponseException e) {
            throw toAurisException(e);
        }
        if (result == null || result.getCode() == null || result.getCode() != 0) {
            throw new AurisException(AIErrorCode.ENGINE_ERROR);
        }
        return result;
    }

    /**
     * engine 的 HTTP 状态码 → AurisException(engine 错误文案在 detail 里,按需透传)
     */
    private AurisException toAurisException(RestClientResponseException e) {
        String detail = extractDetail(e);
        return switch (e.getStatusCode().value()) {
            case 400 -> new AurisException(AIErrorCode.AUDIO_INVALID, detail);
            case 404 -> new AurisException(AIErrorCode.TASK_NOT_FOUND, detail);
            case 413 -> new AurisException(CommonErrorCode.FILE_TOO_LARGE);
            default -> {
                // 5xx 及其他:不透传原文(可能含内部路径),日志留 detail
                log.error("engine 返回异常状态 {}: {}", e.getStatusCode().value(), detail);
                yield new AurisException(AIErrorCode.ENGINE_ERROR);
            }
        };
    }

    /**
     * 从 engine 的错误响应里抠 message(FastAPI HTTPException 的 body 形如 {"detail": "..."} )
     */
    private String extractDetail(RestClientResponseException e) {
        try {
            JsonNode node = new ObjectMapper().readTree(e.getResponseBodyAsString());
            if (node.hasNonNull("detail")) {
                return node.get("detail").asText();
            }
        } catch (Exception ignored) {
            // body 不是 JSON(或读不出来):兜底用原始 body,抠不出来就 null
        }
        String body = e.getResponseBodyAsString();
        return body.isEmpty() ? null : body;
    }

    /**
     * 函数式接口,承载一次 engine 调用(lambda 里不能抛受检异常,这里限定运行时异常即可)
     */
    @FunctionalInterface
    private interface EngineCall<T> extends Supplier<EngineResp<T>> {
    }


    private MultipartBodyBuilder getMultipartBodyBuilder(byte[] audio, String provider) {
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("audio", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        });
        String asrProvider = properties.getProvider();
        if (provider != null && !provider.isEmpty()) {
            asrProvider = provider;
        }
        if (asrProvider != null && !asrProvider.isEmpty()) {
            bodyBuilder.part("provider", asrProvider);
        }
        return bodyBuilder;
    }
}
