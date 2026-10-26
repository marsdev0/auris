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

    /** 自建网关型供应商，必填 baseUrl */
    private static final String PROVIDER_OPENAI_COMPAT = "openai_compat";

    // 默认，当前不涉及租户
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
        // 1. 参数校验：openai_compat 必填 baseUrl。model/voice 无模型概念的场景允许为空
        //    (edge_tts 无 model 等),必填性由前端按 provider 元数据控制,后端不维护特例表
        if (PROVIDER_OPENAI_COMPAT.equals(req.getProvider()) && !StringUtils.hasText(req.getBaseUrl())) {
            throw new AurisException(CommonErrorCode.BAD_REQUEST, "openai_compat requires baseUrl");
        }

        // 2. 组装实体;密文三件套仅换 key 时才有值
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
        // key_version 列 NOT NULL,新行需给当前主密钥版本
        entity.setKeyVersion(apiKeyCipher.currentVersion());
        entity.setStatus(ConfigStatus.UNVERIFIED.getCode());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        // 3. 直接插入;撞 uk_tenant_user_domain 说明该域已有配置,转编辑更新。
        //    避免先查后写的 check-then-act 并发竞态
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
            // 撞唯一键后却查不到旧记录,理论上仅在并发删除时出现
            throw new AurisException(CommonErrorCode.INTERNAL_ERROR, "Config save conflict, please retry");
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
     * 取明文 key 尾 4 位作掩码提示(不足 4 位原样返回，免 key 配置为 null)
     */
    private String maskTail4(String apiKey) {
        return apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
    }

    public Boolean delete(Long userId, ModelDomain domain) {
        int deleted = modelConfigMapper.deleteByTenantUserDomain(tenantId, userId, domain.getValue());
        return deleted > 0;
    }

    /**
     * 1. 先查询(脏 domain 由 of() 拦截,未配置的域在转发前拦截)
     * 2. 内存解密 + 调用 engine 验证(内网明文仅此一程,MVP 仅支持 llm 域)
     * 3. 验证结果落库(status/last_checked_at/失败原因)
     */
    public Boolean validate(Long userId, ModelDomain domain) {
        // 1. 按 uk 查该域已存配置
        UserModelConfigDO config = modelConfigMapper.selectByTenantUserDomain(tenantId, userId, domain.getValue());
        if (config == null) {
            throw new AurisException(CommonErrorCode.BAD_REQUEST, "This domain has no config yet, please save it first");
        }

        // 2. 内存解密(免 key 配置为 null) + 组装转发体(明文仅内网一程) + 调用 engine 实测
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
                // byok 域映射:engine 侧异常统一 502。校验成败由 code 表达,是预期业务分支不在这层拦截
                e -> new AurisException(AIErrorCode.ENGINE_ERROR));

        // 3. 回写校验结果:status/last_checked_at/detail(成功拼延迟,失败给 engine 的 message)
        LambdaUpdateWrapper<UserModelConfigDO> uw = new LambdaUpdateWrapper<UserModelConfigDO>()
                .eq(UserModelConfigDO::getId, config.getId())
                .set(UserModelConfigDO::getStatus, engineResp.isSuccess()
                        ? ConfigStatus.VERIFIED.getCode() : ConfigStatus.FAILED.getCode())
                .set(UserModelConfigDO::getLastCheckedAt, LocalDateTime.now());
        if (engineResp.isSuccess() && engineResp.getData() != null) {
            // latency 不单独建列,拼进 detail("models listed, 87 available · 812ms")
            uw.set(UserModelConfigDO::getValidateDetail,
                    engineResp.getData().getDetail() + " · " + engineResp.getData().getLatencyMs() + "ms");
        } else {
            uw.set(UserModelConfigDO::getValidateDetail, engineResp.getMessage());
        }
        modelConfigMapper.update(null, uw);
        return engineResp.isSuccess();
    }
}
