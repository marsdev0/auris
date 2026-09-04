package com.mars.auris.ai.transcribe;

import com.mars.auris.ai.config.EngineProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.model.TranscribeResp;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author geyan
 * @date 2026/8/29
 */
@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false") // nacos 是注册旁路,测试直接关掉
class TranscribeServiceTest {

    @Autowired
    private TranscribeService transcribeService;

    @Autowired
    private EngineProperties engineProperties;

    /**
     * 只替换跨进程边界(engine),替换的是容器里名为 engineSubmitRestClient 的真实 bean;
     * 其余 bean(Service/Convert/Properties)全部走真实装配
     */
    @MockitoBean(name = "engineSubmitRestClient")
    private RestClient engineSubmit;

    // ========== 辅助方法:mock RestClient 的调用链(POST + multipart) ==========

    /**
     * mock engine 正常返回
     *
     * @return bodySpec,供调用方做 ArgumentCaptor 验证
     */
    private RestClient.RequestBodySpec mockEngineSuccess(AsrResultDTO response) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(engineSubmit.post()).thenReturn(uriSpec);
        when(uriSpec.uri(TranscribeConst.URL_ASR_TRANSCRIBE)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(bodySpec);
        // body 有重载,body(Object) 必须显式 any(Object.class),否则 stub 落到别的重载上 → NPE
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        // Service 用 body(ParameterizedTypeReference) 解 EngineResp 壳,stub 同一重载
        when(responseSpec.body(any(ParameterizedTypeReference.class)))
                .thenReturn(new EngineResp<>(0, "success", response));
        return bodySpec;
    }

    /**
     * mock engine 抛异常(模拟 engine 挂了 / 连接拒绝 / 超时)
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
     * 从 mock 捕获的请求体里取某个 multipart part 的值
     */
    private String capturedPart(RestClient.RequestBodySpec bodySpec, String name) {
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).body(bodyCaptor.capture());
        MultiValueMap<String, HttpEntity<?>> parts = (MultiValueMap<String, HttpEntity<?>>) bodyCaptor.getValue();
        HttpEntity<?> part = parts.getFirst(name);
        return part == null ? null : String.valueOf(part.getBody());
    }

    // ========== 场景 0:容器装配 + 配置真实绑定 ==========

    @Test
    void containerLoads_configBoundFromYaml() {
        // 真容器起来,Service 成功注入;engine.provider 从 application.yaml 真实绑定
        assertNotNull(transcribeService);
        assertEquals("qwen3-asr", engineProperties.getProvider());
    }

    // ========== 场景 1:正常转写 ==========

    @Test
    void transcribeSync_success_returnsText() {
        // 准备:mock engine 正常返回
        AsrResultDTO response = new AsrResultDTO();
        response.setText("你好世界");
        mockEngineSuccess(response);

        // 执行
        TranscribeResp result = transcribeService.transcribeSync("audio-bytes".getBytes(), "whisper");

        // 验证:engine 的全文透传给了前端模型
        assertEquals("你好世界", result.getText());
    }

    // ========== 场景 2:provider 未传,兜底到配置默认值 ==========

    @Test
    void transcribeSync_providerNull_fallsBackToConfig() {
        RestClient.RequestBodySpec bodySpec = mockEngineSuccess(new AsrResultDTO());

        // 执行(provider 传 null)
        transcribeService.transcribeSync("audio-bytes".getBytes(), null);

        // 验证:发出去的 multipart 里 provider part 是配置的默认值
        assertEquals("qwen3-asr", capturedPart(bodySpec, "provider"));
    }

    // ========== 场景 3:provider 显式传入,原样转发 ==========

    @Test
    void transcribeSync_providerSpecified_forwarded() {
        RestClient.RequestBodySpec bodySpec = mockEngineSuccess(new AsrResultDTO());

        transcribeService.transcribeSync("audio-bytes".getBytes(), "whisper");

        assertEquals("whisper", capturedPart(bodySpec, "provider"));
    }

    // ========== 场景 4:engine 返回空 body,抛 ENGINE_ERROR ==========

    @Test
    void transcribeSync_emptyBody_throwsEngineError() {
        // 准备:engine 返回 200 但 body 反序列化为 null
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(engineSubmit.post()).thenReturn(uriSpec);
        when(uriSpec.uri(TranscribeConst.URL_ASR_TRANSCRIBE)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.MULTIPART_FORM_DATA)).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        // 同一重载:body(ParameterizedTypeReference) 返回 null
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(null);

        // 执行 + 验证
        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync("audio-bytes".getBytes(), "whisper"));
        assertEquals(502101, ex.getCode());
    }

    // ========== 场景 5:engine 挂了(连接拒绝)→ 503 ENGINE_UNAVAILABLE ==========
    // P1 方案 §3.4 异常映射:ResourceAccessException 不再原样抛出

    @Test
    void transcribeSync_engineDown_throwsEngineUnavailable() {
        mockEngineThrow(new ResourceAccessException("Connection refused"));

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync("audio-bytes".getBytes(), "whisper"));
        assertEquals(503_101, ex.getCode());
        assertEquals(503, ex.getHttpStatus());
    }

    // ========== 场景 6:engine 返回 404 → 404 TASK_NOT_FOUND(msg 透传 engine 原文) ==========

    @Test
    void transcribeSync_engine404_throwsTaskNotFound() {
        HttpClientErrorException notFound = new HttpClientErrorException(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                "{\"detail\": \"任务不存在或已过期\"}".getBytes(), null);
        mockEngineThrow(notFound);

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync("audio-bytes".getBytes(), "whisper"));
        assertEquals(404_101, ex.getCode());
        assertEquals("任务不存在或已过期", ex.getMsg());
    }

    // ========== 场景 7:engine 返回 400 → 400 AUDIO_INVALID(msg 透传) ==========

    @Test
    void transcribeSync_engine400_throwsAudioInvalid() {
        HttpClientErrorException badRequest = new HttpClientErrorException(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"detail\": \"音频内容为空\"}".getBytes(), null);
        mockEngineThrow(badRequest);

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync("audio-bytes".getBytes(), "whisper"));
        assertEquals(400_101, ex.getCode());
        assertEquals("音频内容为空", ex.getMsg());
    }

    // ========== 场景 8:engine 返回 500 → 502 ENGINE_ERROR(不透传原文) ==========

    @Test
    void transcribeSync_engine500_throwsEngineError_noLeak() {
        HttpClientErrorException serverError = new HttpClientErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY,
                "{\"detail\": \"/usr/local/lib/python3.12/xxx 内部路径\"}".getBytes(), null);
        mockEngineThrow(serverError);

        AurisException ex = assertThrows(AurisException.class,
                () -> transcribeService.transcribeSync("audio-bytes".getBytes(), "whisper"));
        assertEquals(502_101, ex.getCode());
        // 关键:5xx 不透传 engine 原文(可能含内部路径),只给统一文案
        assertEquals("转写引擎内部错误", ex.getMsg());
    }
}
