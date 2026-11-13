// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.auris.ai.byok.model.DubbingGrant;
import com.mars.auris.ai.byok.service.ByokService;
import com.mars.auris.ai.config.StorageProperties;
import com.mars.auris.ai.dubbing.entity.DubbingTaskDO;
import com.mars.auris.ai.dubbing.mapper.DubbingTaskMapper;
import com.mars.auris.ai.dubbing.model.request.DubbingSubmitReq;
import com.mars.auris.ai.engine.EngineClient;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.model.SubmitTaskResp;
import com.mars.auris.common.error.AurisException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DubbingService.submitUrl main-flow unit test (P13 D3): BYOK gate ordering, byok body forwarding,
 * snapshot persistence, uk idempotent reuse. Poll/asset paths are not covered (main flow only).
 *
 * @author geyan
 * @date 2026/11/11
 */
@ExtendWith(MockitoExtension.class)
class DubbingServiceTest {

    private static final Long USER_ID = 7L;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private DubbingTaskMapper mapper;

    @Mock
    private RestClient engineSubmit;

    @Mock
    private RestClient enginePoll;

    @Mock
    private RestClient engineAsset;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private ByokService byokService;

    @Spy
    private EngineClient engineClient = new EngineClient();

    @InjectMocks
    private DubbingService dubbingService;

    @BeforeAll
    static void initMpLambdaCache() {
        // dup-key fallback builds a lambdaQuery on DubbingTaskDO; plain unit test has no SqlSessionFactory
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DubbingTaskDO.class);
    }

    // ========== gate ordering: 403 must precede the insert (uk semantics: row exists = reuse forever) ==========

    @Test
    void submitUrl_byokGateRejected_noRowNoEngineCall() {
        when(byokService.resolveForDubbing(USER_ID))
                .thenThrow(new AurisException(AIErrorCode.MODEL_CONFIG_REQUIRED, "missing: llm,tts"));

        AurisException ex = assertThrows(AurisException.class,
                () -> dubbingService.submitUrl(USER_ID, "https://example.com/ep.mp3"));
        assertEquals(AIErrorCode.MODEL_CONFIG_REQUIRED.getCode(), ex.getCode());
        verify(mapper, never()).insert(any(DubbingTaskDO.class));
        verifyNoInteractions(engineSubmit);
    }

    // ========== happy path: byok travels on the engine hop, snapshot lands on the row ==========

    @Test
    void submitUrl_happyPath_sendsByokBodyAndPersistsSnapshot() throws Exception {
        Map<String, Object> byokBody = new HashMap<>();
        byokBody.put("user_id", USER_ID);
        DubbingGrant grant = new DubbingGrant(byokBody, "{\"llm\":{\"config_id\":9}}");
        when(byokService.resolveForDubbing(USER_ID)).thenReturn(grant);
        doAnswer(inv -> {
            ((DubbingTaskDO) inv.getArgument(0)).setId(123L);
            return 1;
        }).when(mapper).insert(any(DubbingTaskDO.class));
        RestClient.RequestBodySpec bodySpec = mockEngineSubmitOk("e123abc");

        SubmitTaskResp resp = dubbingService.submitUrl(USER_ID, "https://example.com/ep.mp3");

        assertEquals("123", resp.getRecordId());
        // byok forwarded by reference on the inner-network hop
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).body(bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertEquals("https://example.com/ep.mp3", body.get("url"));
        assertSame(byokBody, body.get("byok"));
        // snapshot persisted on the row
        ArgumentCaptor<DubbingTaskDO> insertCaptor = ArgumentCaptor.forClass(DubbingTaskDO.class);
        verify(mapper).insert(insertCaptor.capture());
        assertEquals("{\"llm\":{\"config_id\":9}}", insertCaptor.getValue().getConfigSnapshot());
        verify(mapper).updateById(any(DubbingTaskDO.class));
    }

    // ========== engine reject: row marked failed and trace kept ==========

    @Test
    void submitUrl_engineRejects_marksRowFailed() {
        when(byokService.resolveForDubbing(USER_ID)).thenReturn(new DubbingGrant(new HashMap<>(), "{}"));
        doAnswer(inv -> {
            ((DubbingTaskDO) inv.getArgument(0)).setId(555L);
            return 1;
        }).when(mapper).insert(any(DubbingTaskDO.class));
        mockEngineSubmitThrow(new RestClientResponseException(
                "engine 500", 500, "Internal Server Error", null, null, null));

        assertThrows(AurisException.class,
                () -> dubbingService.submitUrl(USER_ID, "https://example.com/ep.mp3"));

        ArgumentCaptor<DubbingTaskDO> captor = ArgumentCaptor.forClass(DubbingTaskDO.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getStatus());   // FAILED
    }

    // ========== uk idempotency: existing row reused, engine never called again ==========

    @Test
    void submitUrl_duplicateUrl_returnsExistingIdWithoutEngineCall() {
        when(byokService.resolveForDubbing(USER_ID))
                .thenReturn(new DubbingGrant(new HashMap<>(), "{}"));
        doAnswer(inv -> {
            throw new DuplicateKeyException("uk_user_url_hash");
        }).when(mapper).insert(any(DubbingTaskDO.class));
        DubbingTaskDO existing = new DubbingTaskDO();
        existing.setId(888L);
        when(mapper.selectOne(any())).thenReturn(existing);

        SubmitTaskResp resp = dubbingService.submitUrl(USER_ID, "https://example.com/ep.mp3");

        assertEquals("888", resp.getRecordId());
        verifyNoInteractions(engineSubmit);
    }

    // ========== helpers: mock the engine submit chain (JSON body + PTR unwrap) ==========

    private RestClient.RequestBodySpec mockEngineSubmitOk(String engineTaskId) throws Exception {
        RestClient.RequestBodySpec bodySpec = mockEngineSubmitBase();
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        JsonNode data = JSON.readTree("{\"task_id\":\"" + engineTaskId + "\"}");
        when(responseSpec.body(any(ParameterizedTypeReference.class)))
                .thenReturn(new EngineResp<>(0, "success", data));
        return bodySpec;
    }

    private void mockEngineSubmitThrow(RuntimeException ex) {
        RestClient.RequestBodySpec bodySpec = mockEngineSubmitBase();
        when(bodySpec.retrieve()).thenThrow(ex);
    }

    private RestClient.RequestBodySpec mockEngineSubmitBase() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        when(engineSubmit.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/v1/dubbing/task")).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        return bodySpec;
    }
}
