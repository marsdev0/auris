package com.mars.auris.ai.transcribe;

import com.mars.auris.ai.config.EngineProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.model.TranscribeResp;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
import com.mars.auris.ai.transcribe.service.TranscribeRecordService;
import com.mars.auris.ai.transcribe.service.TranscribeService;
import com.mars.auris.common.error.AurisException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author geyan
 * @date 2026/8/29
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false") // nacos 是注册旁路,测试直接关掉
class TranscribeServiceTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private TranscribeService transcribeService;

    @Autowired
    private EngineProperties engineProperties;

    /**
     * Replace only the cross-process boundary (engine); the replaced bean is the real container bean named engineSubmitRestClient;
     * all other beans (Service/Convert/Properties) are real, fully assembled
     */
    @MockitoBean(name = "engineSubmitRestClient")
    private RestClient engineSubmit;

    /**
     * The persistence layer is entirely mocked: the test touches no real DB (DataSource/mapper need no real connection),
     * verifying only "what the Service orchestration called", not "persistence details" (those have their own unit tests in RecordService)
     */
    @MockitoBean
    private TranscribeRecordService recordService;

    // ========== Helpers: mock the RestClient call chain (POST + multipart) ==========

    /**
     * Mock a normal engine return
     *
     * @return bodySpec, for ArgumentCaptor verification by the caller
     */
    private RestClient.RequestBodySpec mockEngineSuccess(AsrResultDTO response) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(engineSubmit.post()).thenReturn(uriSpec);
        when(uriSpec.uri(TranscribeConst.URL_ASR_TRANSCRIBE)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(bodySpec);
        // body is overloaded; body(Object) needs an explicit any(Object.class), otherwise the stub lands on a different overload → NPE
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        // The Service unwraps the EngineResp shell with body(ParameterizedTypeReference); stub the same overload
        when(responseSpec.body(any(ParameterizedTypeReference.class)))
                .thenReturn(new EngineResp<>(0, "success", response));
        return bodySpec;
    }

    /**
     * Mock the engine throwing (simulating engine down / connection refused / timeout)
     */
    private RestClient.RequestBodySpec mockEngineThrow(RuntimeException ex) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        when(engineSubmit.post()).thenReturn(uriSpec);
        when(uriSpec.uri(TranscribeConst.URL_ASR_TRANSCRIBE)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenThrow(ex);
        return bodySpec;
    }

    /**
     * Extract the value of a multipart part from the captured mock request body
     */
    private String capturedPart(RestClient.RequestBodySpec bodySpec, String name) {
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).body(bodyCaptor.capture());
        MultiValueMap<String, HttpEntity<?>> parts = (MultiValueMap<String, HttpEntity<?>>) bodyCaptor.getValue();
        HttpEntity<?> part = parts.getFirst(name);
        return part == null ? null : String.valueOf(part.getBody());
    }

    // ========== Scenario 0: container assembly + real config binding ==========

    @Test
    void containerLoads_configBoundFromYaml() {
        // Real container up, Service successfully injected; engine.provider really bound from application.yaml
        assertNotNull(transcribeService);
        assertEquals("qwen3-asr", engineProperties.getProvider());
    }

    // ========== Scenario 1: normal transcription ==========

    @Test
    void transcribeSync_success_returnsText() {
        // Setup: mock a normal engine return
        AsrResultDTO response = new AsrResultDTO();
        response.setText("你好世界");
        mockEngineSuccess(response);

        // Execute
        TranscribeResp result = transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), "whisper");

        // Verify: the engine's full text passes through to the frontend model
        assertEquals("你好世界", result.getText());
        // Verify: on success, persisted with the current userId (P3 write point ⑤, history)
        verify(recordService).saveTranscribeSyncResult(eq(USER_ID), any(EngineResp.class));
    }

    // ========== Scenario 2: provider not passed, falls back to the configured default ==========

    @Test
    void transcribeSync_providerNull_fallsBackToConfig() {
        RestClient.RequestBodySpec bodySpec = mockEngineSuccess(new AsrResultDTO());

        // Execute (provider is null)
        transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), null);

        // Verify: the provider part in the outgoing multipart is the configured default
        assertEquals("qwen3-asr", capturedPart(bodySpec, "provider"));
    }

    // ========== Scenario 3: provider passed explicitly, forwarded as-is ==========

    @Test
    void transcribeSync_providerSpecified_forwarded() {
        RestClient.RequestBodySpec bodySpec = mockEngineSuccess(new AsrResultDTO());

        transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), "whisper");

        assertEquals("whisper", capturedPart(bodySpec, "provider"));
    }

    // ========== Scenario 4: engine returns empty body → throws ENGINE_ERROR ==========

    @Test
    void transcribeSync_emptyBody_throwsEngineError() {
        // Setup: engine returns 200 but the body deserializes to null
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(engineSubmit.post()).thenReturn(uriSpec);
        when(uriSpec.uri(TranscribeConst.URL_ASR_TRANSCRIBE)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        // Same overload: body(ParameterizedTypeReference) returns null
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(null);

        // Execute + verify
        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), "whisper"));
        assertEquals(502101, ex.getCode());
    }

    // ========== Scenario 5: engine down (connection refused) → 503 ENGINE_UNAVAILABLE ==========
    // P1 plan §3.4 exception mapping: ResourceAccessException is no longer rethrown as-is

    @Test
    void transcribeSync_engineDown_throwsEngineUnavailable() {
        mockEngineThrow(new ResourceAccessException("Connection refused"));

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), "whisper"));
        assertEquals(503_101, ex.getCode());
        assertEquals(503, ex.getHttpStatus());
    }

    // ========== Scenario 6: engine returns 404 → 404 TASK_NOT_FOUND (msg passes through engine's text) ==========

    @Test
    void transcribeSync_engine404_throwsTaskNotFound() {
        HttpClientErrorException notFound = new HttpClientErrorException(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                "{\"detail\": \"任务不存在或已过期\"}".getBytes(), null);
        mockEngineThrow(notFound);

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), "whisper"));
        assertEquals(404_101, ex.getCode());
        assertEquals("任务不存在或已过期", ex.getMsg());
    }

    // ========== Scenario 7: engine returns 400 → 400 AUDIO_INVALID (msg passes through) ==========

    @Test
    void transcribeSync_engine400_throwsAudioInvalid() {
        HttpClientErrorException badRequest = new HttpClientErrorException(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"detail\": \"音频内容为空\"}".getBytes(), null);
        mockEngineThrow(badRequest);

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), "whisper"));
        assertEquals(400_101, ex.getCode());
        assertEquals("音频内容为空", ex.getMsg());
    }

    // ========== Scenario 8: engine returns 500 → 502 ENGINE_ERROR (original text not passed through) ==========

    @Test
    void transcribeSync_engine500_throwsEngineError_noLeak() {
        HttpClientErrorException serverError = new HttpClientErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY,
                "{\"detail\": \"/usr/local/lib/python3.12/xxx 内部路径\"}".getBytes(), null);
        mockEngineThrow(serverError);

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync(USER_ID, "audio-bytes".getBytes(), "whisper"));
        assertEquals(502_101, ex.getCode());
        // Key point: 5xx does not pass through the engine's original text (may contain internal paths); only the unified message
        assertEquals("转写引擎内部错误", ex.getMsg());
    }
}
