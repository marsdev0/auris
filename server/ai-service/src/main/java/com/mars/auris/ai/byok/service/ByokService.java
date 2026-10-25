package com.mars.auris.ai.byok.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mars.auris.ai.byok.convert.ByokConvert;
import com.mars.auris.ai.byok.crypto.ApiKeyCipher;
import com.mars.auris.ai.byok.entity.UserModelConfigDO;
import com.mars.auris.ai.byok.enums.ConfigStatus;
import com.mars.auris.ai.byok.enums.ModelDomain;
import com.mars.auris.ai.byok.mapper.UserModelConfigMapper;
import com.mars.auris.ai.byok.model.engine.EngineValidateReq;
import com.mars.auris.ai.byok.model.engine.EngineValidateResp;
import com.mars.auris.ai.byok.req.SaveConfigReq;
import com.mars.auris.ai.byok.vo.ModelConfigVO;
import com.mars.auris.ai.engine.EngineClient;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author geyan
 * @date 2026/10/10
 */
@Slf4j
@Service
public class ByokService {

    private static final List<ModelDomain> list = List.of(ModelDomain.LLM, ModelDomain.ASR, ModelDomain.TTS);

    /** Self-hosted gateway providers, baseUrl required */
    private static final String PROVIDER_OPENAI_COMPAT = "openai_compat";

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

    public List<ModelConfigVO> list(Long userId) {
        List<ModelConfigVO> res = new ArrayList<>();
        for (ModelDomain item: list) {
            UserModelConfigDO userModelConfigDO = modelConfigMapper.selectByTenantUserDomain(tenantId, userId, item.getValue());
            if (userModelConfigDO != null) {
                res.add(byokConvert.toVO(userModelConfigDO));
            }
        }
        return res;
    }

    public Boolean save(Long userId, SaveConfigReq req) {
        // 1. Param validation: openai_compat requires baseUrl. model/voice allowed empty for model-less scenarios
        //    (edge_tts has no model etc.); requiredness is controlled by the frontend per provider metadata, the backend keeps no special-case table
        if (PROVIDER_OPENAI_COMPAT.equals(req.getProvider()) && !StringUtils.hasText(req.getBaseUrl())) {
            throw new AurisException(CommonErrorCode.BAD_REQUEST, "openai_compat 必须填写 baseUrl");
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
            throw new AurisException(CommonErrorCode.INTERNAL_ERROR, "保存配置冲突, 请重试");
        }

        boolean endpointChanged = !Objects.equals(old.getProvider(), req.getProvider())
                || !Objects.equals(old.getBaseUrl(), req.getBaseUrl());

        LambdaUpdateWrapper<UserModelConfigDO> uw = new LambdaUpdateWrapper<UserModelConfigDO>()
                .eq(UserModelConfigDO::getId, old.getId())
                .set(UserModelConfigDO::getProvider, req.getProvider())
                .set(UserModelConfigDO::getBaseUrl, req.getBaseUrl())
                .set(UserModelConfigDO::getModel, req.getModel())
                .set(UserModelConfigDO::getVoice, req.getVoice())
                .set(UserModelConfigDO::getUpdatedAt, LocalDateTime.now());
        if (keyChanged) {
            uw.set(UserModelConfigDO::getApiKeyCipher, entity.getApiKeyCipher())
                    .set(UserModelConfigDO::getKeyVersion, entity.getKeyVersion())
                    .set(UserModelConfigDO::getKeyHint, entity.getKeyHint())
                    .set(UserModelConfigDO::getStatus, ConfigStatus.UNVERIFIED.getCode());
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
            throw new AurisException(CommonErrorCode.BAD_REQUEST, "该域尚未配置, 请先保存");
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
        EngineResp<EngineValidateResp> engineResp = engineClient.execute(
                () -> engineByok.post()
                        .uri("/byok/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(engineReq)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {}),
                // byok domain mapping: engine-side exceptions uniformly 502. Pass/fail is expressed by code, an expected business branch not intercepted at this layer
                e -> new AurisException(AIErrorCode.ENGINE_ERROR));

        // 3. Write back the result: status/last_checked_at/detail (success appends latency, failure carries engine's message)
        LambdaUpdateWrapper<UserModelConfigDO> uw = new LambdaUpdateWrapper<UserModelConfigDO>()
                .eq(UserModelConfigDO::getId, config.getId())
                .set(UserModelConfigDO::getStatus, engineResp.isSuccess()
                        ? ConfigStatus.VERIFIED.getCode() : ConfigStatus.FAILED.getCode())
                .set(UserModelConfigDO::getLastCheckedAt, LocalDateTime.now());
        if (engineResp.isSuccess() && engineResp.getData() != null) {
            // No separate latency column; appended into detail ("models listed, 87 available · 812ms")
            uw.set(UserModelConfigDO::getValidateDetail,
                    engineResp.getData().getDetail() + " · " + engineResp.getData().getLatencyMs() + "ms");
        } else {
            uw.set(UserModelConfigDO::getValidateDetail, engineResp.getMessage());
        }
        modelConfigMapper.update(null, uw);
        return engineResp.isSuccess();
    }
}
