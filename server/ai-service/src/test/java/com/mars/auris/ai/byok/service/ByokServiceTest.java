// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mars.auris.ai.byok.convert.ByokConvert;
import com.mars.auris.ai.byok.crypto.ApiKeyCipher;
import com.mars.auris.ai.byok.entity.UserModelConfigDO;
import com.mars.auris.ai.byok.enums.ConfigStatus;
import com.mars.auris.ai.byok.enums.ModelDomain;
import com.mars.auris.ai.byok.mapper.UserModelConfigMapper;
import com.mars.auris.ai.byok.model.DubbingGrant;
import com.mars.auris.ai.byok.model.engine.EngineValidateReq;
import com.mars.auris.ai.byok.model.engine.EngineValidateResp;
import com.mars.auris.ai.byok.req.SaveConfigReq;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.engine.EngineClient;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ByokService pure unit test (mock mapper + engine RestClient, real EngineClient spy):
 * covers the two main flows only — save (cipher persist / uk-collision edit) and
 * validate (decrypt-and-forward / result writeback). List/delete are readthroughs, not covered.
 *
 * <p>Security contract under test: api_key plaintext exists only in memory and on the
 * inner-network hop (captured EngineValidateReq); DB receives cipher + tail-4 hint only.
 *
 * @author geyan
 * @date 2026/11/11
 */
@ExtendWith(MockitoExtension.class)
class ByokServiceTest {

    private static final Long USER_ID = 42L;

    private static final byte[] CIPHER = {1, 2, 3, 4};

    @Mock
    private UserModelConfigMapper modelConfigMapper;

    @Mock
    private ByokConvert byokConvert;

    @Mock
    private ApiKeyCipher apiKeyCipher;

    @Mock
    private RestClient engineByok;

    /** Real instance as spy: execute() actually invokes the supplier, so the mocked RestClient chain runs */
    @Spy
    private EngineClient engineClient = new EngineClient();

    @InjectMocks
    private ByokService byokService;

    @BeforeAll
    static void initMpLambdaCache() {
        // LambdaUpdateWrapper with UserModelConfigDO::getXxx method refs needs TableInfo registered;
        // plain unit test has no SqlSessionFactory, so init it once by hand
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserModelConfigDO.class);
    }

    // ========== save: first insert (cipher + hint + unverified) ==========

    @Test
    void save_firstTime_persistsCipherHintAndUnverified() {
        SaveConfigReq req = new SaveConfigReq();
        req.setDomain(ModelDomain.LLM);
        req.setProvider("openai_compat");
        req.setBaseUrl("https://api.example.com/v1");
        req.setApiKey("sk-abcd1234efgh5678");
        req.setModel("qwen-plus");
        when(apiKeyCipher.encrypt("sk-abcd1234efgh5678")).thenReturn(CIPHER);
        when(apiKeyCipher.currentVersion()).thenReturn(2);
        when(modelConfigMapper.insert(any(UserModelConfigDO.class))).thenReturn(1);

        assertTrue(byokService.save(USER_ID, req));

        ArgumentCaptor<UserModelConfigDO> captor = ArgumentCaptor.forClass(UserModelConfigDO.class);
        verify(modelConfigMapper).insert(captor.capture());
        UserModelConfigDO saved = captor.getValue();
        // Plaintext never lands: only cipher + tail-4 hint; status starts unverified; tenant column reserved as 0
        assertSame(CIPHER, saved.getApiKeyCipher());
        assertEquals("5678", saved.getKeyHint());
        assertEquals(2, saved.getKeyVersion());
        assertEquals(ConfigStatus.UNVERIFIED.getCode(), saved.getStatus());
        assertEquals(0L, saved.getTenantId());
        assertEquals(USER_ID, saved.getUserId());
        assertEquals("llm", saved.getDomain());
        assertEquals("openai_compat", saved.getProvider());
        assertEquals("https://api.example.com/v1", saved.getBaseUrl());
        assertEquals("qwen-plus", saved.getModel());
    }

    @Test
    void save_openaiCompatWithoutBaseUrl_rejectedBeforeDb() {
        SaveConfigReq req = new SaveConfigReq();
        req.setDomain(ModelDomain.LLM);
        req.setProvider("openai_compat");   // baseUrl left null: whitelist passes, baseUrl rule rejects

        AurisException ex = assertThrows(AurisException.class, () -> byokService.save(USER_ID, req));
        assertEquals(CommonErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verifyNoInteractions(modelConfigMapper);
    }

    // ========== save: provider whitelist (02 方案 §3.2, per-domain) ==========

    @Test
    void save_asrProviderNotImplemented_rejectedBeforeDb() {
        // dashscope asr (Filetrans) has no engine plugin yet: off the whitelist,
        // rejected here instead of surfacing as a confusing validate failure later
        SaveConfigReq req = new SaveConfigReq();
        req.setDomain(ModelDomain.ASR);
        req.setProvider("dashscope");
        req.setBaseUrl("https://dashscope.aliyuncs.com");

        AurisException ex = assertThrows(AurisException.class, () -> byokService.save(USER_ID, req));
        assertEquals(CommonErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verifyNoInteractions(modelConfigMapper);
    }

    @Test
    void save_ttsFishAudioNotImplemented_rejectedBeforeDb() {
        SaveConfigReq req = new SaveConfigReq();
        req.setDomain(ModelDomain.TTS);
        req.setProvider("fish_audio");
        req.setApiKey("sk-xxxxxxxx12345678");

        AurisException ex = assertThrows(AurisException.class, () -> byokService.save(USER_ID, req));
        assertEquals(CommonErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verifyNoInteractions(modelConfigMapper);
    }

    @Test
    void save_deepseekAsr_withoutModel_persists() {
        // deepseek-asr has no model concept: model left null must pass the whitelist + persist
        SaveConfigReq req = new SaveConfigReq();
        req.setDomain(ModelDomain.ASR);
        req.setProvider("deepseek");
        req.setBaseUrl("https://api.deepseek.com");
        req.setApiKey("sk-abcd1234efgh5678");
        when(apiKeyCipher.encrypt("sk-abcd1234efgh5678")).thenReturn(CIPHER);
        when(apiKeyCipher.currentVersion()).thenReturn(2);
        when(modelConfigMapper.insert(any(UserModelConfigDO.class))).thenReturn(1);

        assertTrue(byokService.save(USER_ID, req));

        ArgumentCaptor<UserModelConfigDO> captor = ArgumentCaptor.forClass(UserModelConfigDO.class);
        verify(modelConfigMapper).insert(captor.capture());
        assertEquals("asr", captor.getValue().getDomain());
        assertEquals("deepseek", captor.getValue().getProvider());
        assertNull(captor.getValue().getModel());
    }

    // ========== save: uk collision -> edit existing ==========

    @Test
    void save_duplicateKey_fallsBackToEditExisting() {
        SaveConfigReq req = new SaveConfigReq();
        req.setDomain(ModelDomain.LLM);
        req.setProvider("openai_compat");
        req.setBaseUrl("https://api.example.com/v1");
        req.setApiKey(null);   // key untouched: keep stored cipher, no re-encrypt
        when(modelConfigMapper.insert(any(UserModelConfigDO.class)))
                .thenThrow(new DuplicateKeyException("uk_tenant_user_domain"));
        UserModelConfigDO existing = new UserModelConfigDO();
        existing.setId(7L);
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(existing);

        assertTrue(byokService.save(USER_ID, req));

        verify(modelConfigMapper).selectByTenantUserDomain(0L, USER_ID, "llm");
        verify(modelConfigMapper).update(isNull(), any());
    }

    @Test
    void save_duplicateKeyButRowMissing_throwsInternalError() {
        SaveConfigReq req = new SaveConfigReq();
        req.setDomain(ModelDomain.LLM);
        req.setProvider("openai_compat");
        req.setBaseUrl("https://api.example.com/v1");
        when(modelConfigMapper.insert(any(UserModelConfigDO.class)))
                .thenThrow(new DuplicateKeyException("uk_tenant_user_domain"));
        // Unique key hit but row gone: only possible under concurrent delete, beyond the model
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(null);

        AurisException ex = assertThrows(AurisException.class, () -> byokService.save(USER_ID, req));
        assertEquals(CommonErrorCode.INTERNAL_ERROR.getCode(), ex.getCode());
    }

    // ========== validate: decrypt in memory -> forward plaintext -> write back ==========

    @Test
    void validate_success_forwardsDecryptedKeyAndWritesBack() {
        UserModelConfigDO config = storedLlmConfig();
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(config);
        when(apiKeyCipher.decrypt(CIPHER)).thenReturn("sk-plain-9f2c");
        EngineValidateResp data = new EngineValidateResp();
        data.setLatencyMs(812);
        data.setDetail("models listed, 87 available");
        RestClient.RequestBodySpec bodySpec = mockEngineValidate(new EngineResp<>(0, "success", data));

        assertTrue(byokService.validate(USER_ID, ModelDomain.LLM));

        // The security boundary: plaintext travels only on this inner-network body, field-by-field passthrough
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).body(bodyCaptor.capture());
        EngineValidateReq forwarded = (EngineValidateReq) bodyCaptor.getValue();
        assertEquals("llm", forwarded.getDomain());
        assertEquals("openai_compat", forwarded.getProvider());
        assertEquals("https://dashscope.example.com/compatible-mode/v1", forwarded.getBaseUrl());
        assertEquals("sk-plain-9f2c", forwarded.getApiKey());
        assertEquals("qwen-plus", forwarded.getModel());
        // Writeback executed (status/detail persisted via LambdaUpdateWrapper)
        verify(modelConfigMapper).update(isNull(), any());
    }

    @Test
    void validate_engineBusinessFailure_returnsFalseAndStillWritesBack() {
        UserModelConfigDO config = storedLlmConfig();
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(config);
        when(apiKeyCipher.decrypt(CIPHER)).thenReturn("sk-plain-9f2c");
        // Engine probe fails as business result (HTTP 200, code=1, reason in message): no exception, false + writeback
        mockEngineValidate(new EngineResp<>(1, "鉴权失败：api_key 无效或已过期", null));

        assertEquals(false, byokService.validate(USER_ID, ModelDomain.LLM));
        verify(modelConfigMapper).update(isNull(), any());
    }

    @Test
    void validate_noConfig_throwsBadRequestWithoutEngineCall() {
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(null);

        AurisException ex = assertThrows(AurisException.class,
                () -> byokService.validate(USER_ID, ModelDomain.LLM));
        assertEquals(CommonErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verifyNoInteractions(engineByok);
    }

    // ========== resolveForDubbing (D3): llm/tts gate + decrypt-and-forward + snapshot ==========

    @Test
    void resolveForDubbing_missingLlm_throws403() {
        // tts verified but llm absent: dubbing gate must reject (403) and name the missing domain
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(null);
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "tts")).thenReturn(verifiedTts());
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "asr")).thenReturn(null);

        AurisException ex = assertThrows(AurisException.class,
                () -> byokService.resolveForDubbing(USER_ID));
        assertEquals(AIErrorCode.MODEL_CONFIG_REQUIRED.getCode(), ex.getCode());
        assertEquals(403, ex.getHttpStatus());
        assertTrue(ex.getMsg().contains("llm"));
    }

    @Test
    void resolveForDubbing_unverifiedTts_throws403() {
        // Unverified counts as missing (§2.3: "可存不可用")
        UserModelConfigDO unverified = storedLlmConfig();
        unverified.setStatus(ConfigStatus.UNVERIFIED.getCode());
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(storedLlmConfig());
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "tts")).thenReturn(unverified);
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "asr")).thenReturn(null);

        AurisException ex = assertThrows(AurisException.class,
                () -> byokService.resolveForDubbing(USER_ID));
        assertEquals(AIErrorCode.MODEL_CONFIG_REQUIRED.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("tts"));
    }

    @Test
    void resolveForDubbing_ok_forwardsDecryptedKeys_asrOptional() {
        // llm+tts verified, asr absent (falls back to platform): byok body carries plaintext,
        // snapshot carries config_id/model only — the two never mix
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "llm")).thenReturn(storedLlmConfig());
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "tts")).thenReturn(verifiedTts());
        when(modelConfigMapper.selectByTenantUserDomain(0L, USER_ID, "asr")).thenReturn(null);
        when(apiKeyCipher.decrypt(CIPHER)).thenReturn("sk-plain-9f2c");

        DubbingGrant grant = byokService.resolveForDubbing(USER_ID);

        assertEquals(USER_ID, grant.getEngineByok().get("user_id"));
        assertFalse(grant.getEngineByok().containsKey("asr"));
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) grant.getEngineByok().get("llm");
        assertEquals("sk-plain-9f2c", llm.get("api_key"));
        assertEquals("openai_compat", llm.get("provider"));
        assertEquals("https://dashscope.example.com/compatible-mode/v1", llm.get("base_url"));
        assertEquals("qwen-plus", llm.get("model"));
        assertNotNull(llm.get("config_hash"));
        assertEquals(32, String.valueOf(llm.get("config_hash")).length());   // md5 hex

        String snapshot = grant.getSnapshot();
        assertTrue(snapshot.contains("\"config_id\""));
        assertTrue(snapshot.contains("qwen-plus"));
        assertFalse(snapshot.contains("api_key"));
        assertFalse(snapshot.contains("sk-plain-9f2c"));
    }

    // ========== helpers ==========

    private UserModelConfigDO verifiedTts() {
        UserModelConfigDO tts = new UserModelConfigDO();
        tts.setId(10L);
        tts.setTenantId(0L);
        tts.setUserId(USER_ID);
        tts.setDomain("tts");
        tts.setProvider("edge_tts");
        tts.setVoice("zh-CN-XiaoxiaoNeural");
        tts.setStatus(ConfigStatus.VERIFIED.getCode());
        return tts;
    }

    private UserModelConfigDO storedLlmConfig() {
        UserModelConfigDO config = new UserModelConfigDO();
        config.setId(9L);
        config.setTenantId(0L);
        config.setUserId(USER_ID);
        config.setDomain("llm");
        config.setProvider("openai_compat");
        config.setBaseUrl("https://dashscope.example.com/compatible-mode/v1");
        config.setApiKeyCipher(CIPHER);
        config.setModel("qwen-plus");
        config.setStatus(ConfigStatus.VERIFIED.getCode());
        return config;
    }

    /**
     * Mock the engine /byok/validate RestClient chain (same discipline as TranscribeServiceTest):
     * JSON body + ParameterizedTypeReference unwrap; returns the bodySpec so callers can capture the body.
     */
    private RestClient.RequestBodySpec mockEngineValidate(EngineResp<EngineValidateResp> resp) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(engineByok.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/byok/validate")).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(resp);
        return bodySpec;
    }
}
