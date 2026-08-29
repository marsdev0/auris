// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe;

import com.mars.auris.ai.config.EngineProperties;
import com.mars.auris.ai.convert.TranscribeConvert;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.model.TranscribeResult;
import com.mars.auris.ai.model.engine.EngineAsrResult;
import com.mars.auris.common.error.AurisException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private TranscribeConvert convert;

    @Autowired
    private EngineProperties properties;

    /**
     * 同步返回
     */
    public TranscribeResult transcribeSync(byte[] audio, String provider) {
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

        EngineAsrResult result = engineSubmit.post()
                .uri(TranscribeConst.URL_TRANSCRIBE)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(bodyBuilder.build())
                .retrieve()
                .body(EngineAsrResult.class);
        if (result == null) {
            throw new AurisException(AIErrorCode.ENGINE_ERROR);
        }
        return convert.toResult(result);
    }
}
