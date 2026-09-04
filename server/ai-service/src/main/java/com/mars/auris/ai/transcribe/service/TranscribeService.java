// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.service;

import com.mars.auris.ai.config.EngineProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.convert.TranscribeConvert;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.transcribe.model.LongTaskResp;
import com.mars.auris.ai.transcribe.model.SubmitTaskResp;
import com.mars.auris.ai.transcribe.model.TranscribeResp;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
import com.mars.auris.ai.transcribe.model.engine.AsrTaskDTO;
import com.mars.auris.common.error.AurisException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * @author geyan
 * @date 2026/8/28
 */
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

    /**
     * 同步返回
     */
    public TranscribeResp transcribeSync(byte[] audio, String provider) {
        MultipartBodyBuilder bodyBuilder = getMultipartBodyBuilder(audio, provider);
        EngineResp<AsrResultDTO> result = engineSubmit.post()
                .uri(TranscribeConst.URL_ASR_TRANSCRIBE)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(bodyBuilder.build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result == null || result.getCode() != 0) {
            throw new AurisException(AIErrorCode.ENGINE_ERROR);
        }
        return convert.to(result.getData());
    }


    public SubmitTaskResp submitTask(byte[] audio, String provider) {
        MultipartBodyBuilder bodyBuilder = getMultipartBodyBuilder(audio, provider);
        EngineResp<String> result = engineSubmit.post()
                .uri(TranscribeConst.URL_ASR_TASK_START)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(bodyBuilder.build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result == null || result.getCode() != 0) {
            throw new AurisException(AIErrorCode.ENGINE_ERROR);
        }
        return new SubmitTaskResp(result.getData());
    }


    public LongTaskResp getTask(String taskId) {
        EngineResp<AsrTaskDTO> result = enginePoll.get()
                .uri(TranscribeConst.URL_ASR_TASK_GET + taskId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result == null || result.getCode() != 0) {
            throw new AurisException(AIErrorCode.ENGINE_ERROR);
        }
        return convert.to(result.getData());
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
