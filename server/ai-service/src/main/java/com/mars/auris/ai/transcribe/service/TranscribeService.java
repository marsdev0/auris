// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mars.auris.ai.config.EngineProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.convert.TranscribeConvert;
import com.mars.auris.ai.engine.EngineClient;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import com.mars.auris.ai.transcribe.model.LongTaskResp;
import com.mars.auris.ai.transcribe.model.PageResp;
import com.mars.auris.ai.transcribe.model.RecordItemResp;
import com.mars.auris.ai.transcribe.model.SubmitTaskResp;
import com.mars.auris.ai.transcribe.model.TranscribeResp;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
import com.mars.auris.ai.transcribe.producer.TranscribeEventProducer;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private EngineClient engineClient;

    @Autowired
    private EngineProperties properties;

    @Autowired
    private TranscribeRecordService recordService;

    @Autowired
    private TranscribeEventProducer eventProducer;

    /**
     * Synchronous return
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


    public SubmitTaskResp submitTask(Long userId, byte[] audio, String provider, String originalFilename) {
        EngineResp<String> result = callEngine(() -> engineSubmit.post()
                .uri(TranscribeConst.URL_ASR_TASK_START)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(getMultipartBodyBuilder(audio, provider).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        Long recordId = recordService.createTask(userId, result.getData(),
                TranscribeConst.SOURCE_UPLOAD_ASYNC, basename(originalFilename));
        return new SubmitTaskResp(String.valueOf(recordId));
    }

    public SubmitTaskResp submitUrl(Long userId, String url) {
        EngineResp<String> result = callEngine(() -> engineSubmit.post()
                .uri(TranscribeConst.URL_ASR_TASK_START_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", url))                 // provider 用默认,不透出
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        // URL-path title is backfilled after download completes; empty at insert time
        Long recordId = recordService.createTask(userId, result.getData(), TranscribeConst.SOURCE_URL, null);
        return new SubmitTaskResp(String.valueOf(recordId));
    }

    /**
     * Display name for uploaded files: basename of originalFilename, truncated to 256 (raw value may carry a path / be overlong / be null)
     */
    private String basename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return null;
        }
        String name = originalFilename.substring(Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\')) + 1);
        return name.length() > 256 ? name.substring(0, 256) : name;
    }


    public LongTaskResp getRecord(Long userId, Long recordId) {
        // 1. Check DB first: terminal state → return directly (also verify userId); non-terminal → continue to engine
        TranscribeRecordDO record = recordService.findByUserIdAndRecordId(userId, recordId);
        if (record == null) {
            // Not found / not the current user's / deleted — same code, same message, no existence leak (P3 §2.5)
            throw new AurisException(AIErrorCode.RECORD_NOT_FOUND);
        }
        if (record.getStatus() != 0) {
            // Terminal state (completed/failed) returns from DB directly, no engine call
            return convert.to(record);
        }

        EngineResp<AsrTaskDTO> result;
        try {
            result = callEngine(() -> enginePoll.get()
                    .uri(TranscribeConst.URL_ASR_TASK_GET + record.getEngineTaskId())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    }));
        } catch (AurisException e) {
            if (Objects.equals(e.getCode(), AIErrorCode.TASK_NOT_FOUND.getCode())) {
                recordService.fail(userId, record.getId(), e.getMsg(), null);
                eventProducer.publishTranscribeTerminal(userId, record.getId(), null, "timeout");
            }
            throw e;
        }
        if (TranscribeConst.ENGINE_STATUS_COMPLETED.equals(result.getData().getStatus())) {
            recordService.complete(userId, record.getId(), result.getData());
            eventProducer.publishTranscribeTerminal(userId, record.getId(), result.getData().getTitle(), "completed");
        } else if (TranscribeConst.ENGINE_STATUS_FAILED.equals(result.getData().getStatus())) {
            // The authoritative failure source is the task detail's error field (EngineResp.message is always empty on successful polls)
            recordService.fail(userId, record.getId(), result.getData().getError(), result.getData().getTitle());
            eventProducer.publishTranscribeTerminal(userId, record.getId(), result.getData().getTitle(), "failed");
        }

        LongTaskResp resp = convert.to(result.getData());
        resp.setRecordId(String.valueOf(recordId));
        // error → errorMsg field-name mismatch, MapStruct doesn't map it; the poll that discovers failure must also carry the reason
        resp.setErrorMsg(result.getData().getError());
        return resp;
    }


    public void deleteRecord(Long userId, Long recordId) {
        // Query-then-delete is pointless:
        // if the condition isn't met or the row doesn't exist, nothing is deleted
        // and adding a query introduces a concurrency problem, TOCTOU
//        TranscribeRecordDO item = recordService.findByUserIdAndRecordId(userId, recordId);
//        if (item == null) {
//            throw new AurisException(AIErrorCode.TASK_NOT_FOUND);
//        }
        if (!recordService.deleteByUserIdAndRecordId(userId, recordId)) {
            throw new AurisException(AIErrorCode.RECORD_NOT_FOUND);
        }
    }


    public PageResp<RecordItemResp> getRecords(Long userId, int page, int size) {
        long p = Math.max(page, 1);
        long s = size < 1 ? 10 : Math.min(size, 50);
        Page<TranscribeRecordDO> result = recordService.pageByUserId(userId, p, s);
        List<RecordItemResp> items = result.getRecords().stream()
                .map(convert::toItem).toList();

        return new PageResp<>(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }


    // ==================== Internal logic ===================================

    /**
     * Unified engine call exit: HTTP layer delegates to the EngineClient skeleton; this domain keeps the code check (non-0 = transcription failed = request failed)
     * Status-code mapping table (P1 plan §3.4):
     *   400 empty content/decode failure → AUDIO_INVALID (msg passes through the engine's original text)
     *   404 task not found/expired → TASK_NOT_FOUND; 413 → FILE_TOO_LARGE
     *   5xx → ENGINE_ERROR (original text not passed through — may contain internal paths; detail kept in logs)
     *   unreachable/timeout → ENGINE_UNAVAILABLE
     */
    private <T> EngineResp<T> callEngine(Supplier<EngineResp<T>> call) {
        EngineResp<T> result = engineClient.execute(call, this::toAurisException);
        if (result == null || !result.isSuccess()) {
            throw new AurisException(AIErrorCode.ENGINE_ERROR);
        }
        return result;
    }

    /**
     * Engine HTTP status code → AurisException (engine error text in detail, pass through as needed)
     */
    private AurisException toAurisException(RestClientResponseException e) {
        String detail = engineClient.extractDetail(e);
        return switch (e.getStatusCode().value()) {
            case 400 -> new AurisException(AIErrorCode.AUDIO_INVALID, detail);
            case 404 -> new AurisException(AIErrorCode.TASK_NOT_FOUND, detail);
            case 413 -> new AurisException(CommonErrorCode.FILE_TOO_LARGE);
            default -> new AurisException(AIErrorCode.ENGINE_ERROR);
        };
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
