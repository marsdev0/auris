// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.mars.auris.ai.byok.vo.ModelConfigVO;
import com.mars.auris.ai.engine.EngineClient;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import com.mars.auris.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @author geyan
 * @date 2026/10/10
 */
@Slf4j
@Service
public class ByokService {

    private static final List<ModelDomain> list = List.of(ModelDomain.LLM, ModelDomain.ASR, ModelDomain.TTS);

    /**
     * Provider whitelists per domain (02 方案 §3.2): three-way sync with the frontend provider
     * arrays and the engine byok/factory branches. Unimplemented providers (fish_audio,
     * dashscope-asr/Filetrans) stay off the lists until their engine plugins land — then all
     * three sources update together in one release.
     */
    private static final Set<String> LLM_PROVIDERS = Set.of("openai_compat", "dashscope");
    private static final Set<String> TTS_PROVIDERS = Set.of("edge_tts", "openai_tts", "openai_compat");
    private static final Set<String> ASR_PROVIDERS = Set.of("deepseek", "siliconflow", "glm", "openai_compat");

    /** Providers that need no baseUrl (only the keyless free channel edge_tts) */
    private static final Set<String> BASE_URL_EXEMPT = Set.of("edge_tts");

    // Default; no tenants for now
    private static final Long tenantId = 0L;

    @Autowired
    private UserModelConfigMapper modelConfigMapper;

    @Autowired
    private ByokConvert byokConvert;

    @Autowired
    private ApiKeyCipher apiKeyCipher;

    @Autowired
    @Qualifier("engineByok")
    private RestClient engineByok;

    @Autowired
    private EngineClient engineClient;

    /** Domain -> implemented provider whitelist (02 方案 §3.2; mirrors frontend arrays) */
    private static Set<String> whitelistFor(ModelDomain domain) {
        return switch (domain) {
            case LLM -> LLM_PROVIDERS;
            case TTS -> TTS_PROVIDERS;
            case ASR -> ASR_PROVIDERS;
        };
    }

    public List<ModelConfigVO> list(Long userId) {
        List<ModelConfigVO> res = new ArrayList<>();
        for (ModelDomain item : list) {
            UserModelConfigDO userModelConfigDO = modelConfigMapper.selectByTenantUserDomain(tenantId, userId, item.getValue());
            if (userModelConfigDO != null) {
                res.add(byokConvert.toVO(userModelConfigDO));
            }
        }
        return res;
    }

    public Boolean save(Long userId, SaveConfigReq req) {
        // 1. Param validation: per-domain provider whitelist + baseUrl required (edge_tts exempt).
        //    model/voice allowed empty for model-less providers (edge_tts, deepseek-asr have no model);
        //    requiredness beyond this is controlled by the frontend per provider metadata.
        Set<String> whitelist = whitelistFor(req.getDomain());
        if (!whitelist.contains(req.getProvider())) {
            throw new AurisException(CommonErrorCode.BAD_REQUEST,
                    "unsupported " + req.getDomain().getValue() + " provider: " + req.getProvider()
                            + ", expected one of " + whitelist);
        }
        if (!BASE_URL_EXEMPT.contains(req.getProvider()) && !StringUtils.hasText(req.getBaseUrl())) {
            throw new AurisException(CommonErrorCode.BAD_REQUEST,
                    req.getProvider() + " requires baseUrl");
        }

        // 2. Assemble entity; the ciphertext trio is set only when the key changes
        UserModelConfigDO entity = new UserModelConfigDO();
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        entity.setDomain(req.getDomain().getValue());
        entity.setProvider(req.getProvider());
        entity.setBaseUrl(req.getBaseUrl());
        entity.setModel(req.getModel());
        entity.setVoice(req.getVoice());
        boolean keyChanged = StringUtils.hasText(req.getApiKey());
        if (keyChanged) {
            entity.setApiKeyCipher(apiKeyCipher.encrypt(req.getApiKey()));
            entity.setKeyHint(maskTail4(req.getApiKey()));
        }
        // key_version column is NOT NULL; new rows need the current master key version
        entity.setKeyVersion(apiKeyCipher.currentVersion());
        entity.setStatus(ConfigStatus.UNVERIFIED.getCode());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        // 3. Insert directly; hitting uk_tenant_user_domain means the domain already has a config, switch to update.
        //    Avoids the check-then-act concurrency race of query-then-insert
        try {
            modelConfigMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            return updateExisting(req, entity, keyChanged);
        }
    }

    private Boolean updateExisting(SaveConfigReq req, UserModelConfigDO entity, boolean keyChanged) {
        UserModelConfigDO old = modelConfigMapper.selectByTenantUserDomain(tenantId, entity.getUserId(), entity.getDomain());
        if (old == null) {
            // Hit the unique key but the old record is gone — only possible under concurrent deletion
            throw new AurisException(CommonErrorCode.INTERNAL_ERROR, "Config save conflict, please retry");
        }

        boolean endpointChanged = !Objects.equals(old.getProvider(), req.getProvider()) || !Objects.equals(old.getBaseUrl(), req.getBaseUrl());

        LambdaUpdateWrapper<UserModelConfigDO> uw = new LambdaUpdateWrapper<UserModelConfigDO>().eq(UserModelConfigDO::getId, old.getId()).set(UserModelConfigDO::getProvider, req.getProvider()).set(UserModelConfigDO::getBaseUrl, req.getBaseUrl()).set(UserModelConfigDO::getModel, req.getModel()).set(UserModelConfigDO::getVoice, req.getVoice()).set(UserModelConfigDO::getUpdatedAt, LocalDateTime.now());
        if (keyChanged) {
            uw.set(UserModelConfigDO::getApiKeyCipher, entity.getApiKeyCipher()).set(UserModelConfigDO::getKeyVersion, entity.getKeyVersion()).set(UserModelConfigDO::getKeyHint, entity.getKeyHint()).set(UserModelConfigDO::getStatus, ConfigStatus.UNVERIFIED.getCode());
        } else if (endpointChanged) {
            uw.set(UserModelConfigDO::getStatus, ConfigStatus.UNVERIFIED.getCode());
        }
        modelConfigMapper.update(null, uw);
        return true;
    }

    /**
     * Take the last 4 chars of the plaintext key as a mask hint (returned as-is if shorter, null for key-free configs)
     */
    private String maskTail4(String apiKey) {
        return apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
    }

    public Boolean delete(Long userId, ModelDomain domain) {
        int deleted = modelConfigMapper.deleteByTenantUserDomain(tenantId, userId, domain.getValue());
        return deleted > 0;
    }

    /**
     * 1. Query first (dirty domain intercepted by of(), unconfigured domains intercepted before forwarding)
     * 2. Decrypt in memory + call engine to validate (plaintext exists only on this inner-network hop; MVP supports llm domain only)
     * 3. Persist the validation result (status/last_checked_at/failure reason)
     */
    public Boolean validate(Long userId, ModelDomain domain) {
        // 1. Query the stored config for this domain by uk
        UserModelConfigDO config = modelConfigMapper.selectByTenantUserDomain(tenantId, userId, domain.getValue());
        if (config == null) {
            throw new AurisException(CommonErrorCode.BAD_REQUEST, "This domain has no config yet, please save it first");
        }

        // 2. Decrypt in memory (null for key-free) + assemble forwarding body (plaintext only on the inner hop) + call engine for a live check
        String apiKey = config.getApiKeyCipher() != null ? apiKeyCipher.decrypt(config.getApiKeyCipher()) : null;
        EngineValidateReq engineReq = new EngineValidateReq();
        engineReq.setDomain(domain.getValue());
        engineReq.setProvider(config.getProvider());
        engineReq.setBaseUrl(config.getBaseUrl());
        engineReq.setApiKey(apiKey);
        engineReq.setModel(config.getModel());
        engineReq.setVoice(config.getVoice());
        EngineResp<EngineValidateResp> engineResp = engineClient.execute(() -> engineByok.post().uri("/byok/validate").contentType(MediaType.APPLICATION_JSON).body(engineReq).retrieve().body(new ParameterizedTypeReference<>() {
                }),
                // byok domain mapping: engine-side exceptions uniformly 502. Pass/fail is expressed by code, an expected business branch not intercepted at this layer
                e -> new AurisException(AIErrorCode.ENGINE_ERROR));

        // 3. Write back the result: status/last_checked_at/detail (success appends latency, failure carries engine's message)
        LambdaUpdateWrapper<UserModelConfigDO> uw = new LambdaUpdateWrapper<UserModelConfigDO>().eq(UserModelConfigDO::getId, config.getId()).set(UserModelConfigDO::getStatus, engineResp.isSuccess() ? ConfigStatus.VERIFIED.getCode() : ConfigStatus.FAILED.getCode()).set(UserModelConfigDO::getLastCheckedAt, LocalDateTime.now());
        if (engineResp.isSuccess() && engineResp.getData() != null) {
            // No separate latency column; appended into detail ("models listed, 87 available · 812ms")
            uw.set(UserModelConfigDO::getValidateDetail, engineResp.getData().getDetail() + " · " + engineResp.getData().getLatencyMs() + "ms");
        } else {
            uw.set(UserModelConfigDO::getValidateDetail, engineResp.getMessage());
        }
        modelConfigMapper.update(null, uw);
        return engineResp.isSuccess();
    }

    public DubbingGrant resolveForDubbing(Long userId) {
        List<String> missing = new ArrayList<>();
        Map<String, Object> byok = new LinkedHashMap<>();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        byok.put("user_id", userId);
        for (ModelDomain domain : list) {
            UserModelConfigDO cfg = modelConfigMapper.selectByTenantUserDomain(tenantId, userId, domain.getValue());
            boolean usable = cfg != null && cfg.getStatus() == ConfigStatus.VERIFIED.getCode();
            if (!usable) {
                if (domain != ModelDomain.ASR) {
                    missing.add(domain.getValue());   // llm/tts gate; asr silently falls back
                }
                continue;
            }
            String apiKey = cfg.getApiKeyCipher() != null ? apiKeyCipher.decrypt(cfg.getApiKeyCipher()) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", cfg.getProvider());
            m.put("base_url", cfg.getBaseUrl());
            m.put("api_key", apiKey);
            m.put("model", cfg.getModel());
            if (cfg.getVoice() != null) {
                m.put("voice", cfg.getVoice());
            }
            m.put("config_hash", configHash(cfg));
            byok.put(domain.getValue(), m);

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("config_id", cfg.getId());
            s.put("provider", cfg.getProvider());
            s.put("model", cfg.getModel());
            if (cfg.getVoice() != null) {
                s.put("voice", cfg.getVoice());
            }
            snapshot.put(domain.getValue(), s);
        }
        if (!missing.isEmpty()) {
            throw new AurisException(AIErrorCode.MODEL_CONFIG_REQUIRED,
                    "missing or unverified model config: " + String.join(",", missing));
        }
        return new DubbingGrant(byok, JsonUtils.toJson(snapshot));
    }

    private String configHash(UserModelConfigDO cfg) {
        String cipherB64 = cfg.getApiKeyCipher() == null
                ? "" : Base64.getEncoder().encodeToString(cfg.getApiKeyCipher());
        String material = String.join("|",
                nullToEmpty(cfg.getProvider()), nullToEmpty(cfg.getBaseUrl()),
                nullToEmpty(cfg.getModel()), nullToEmpty(cfg.getVoice()), cipherB64);
        return DigestUtils.md5DigestAsHex(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
