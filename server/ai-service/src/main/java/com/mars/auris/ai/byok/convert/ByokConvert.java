package com.mars.auris.ai.byok.convert;

import com.mars.auris.ai.byok.entity.UserModelConfigDO;
import com.mars.auris.ai.byok.enums.ConfigStatus;
import com.mars.auris.ai.byok.enums.ModelDomain;
import com.mars.auris.ai.byok.vo.ModelConfigVO;
import org.mapstruct.Mapper;

/**
 * DO → VO. Key governance: ciphertext never leaves the DB; echo shows mask only (sk-*** is a unified placeholder prefix, not the real one)
 *
 * @author geyan
 * @date 2026/10/10
 */
@Mapper(componentModel = "spring")
public interface ByokConvert {

    default ModelConfigVO toVO(UserModelConfigDO config) {
        ModelConfigVO vo = new ModelConfigVO();
        vo.setDomain(ModelDomain.of(config.getDomain()));
        vo.setProvider(config.getProvider());
        vo.setBaseUrl(config.getBaseUrl());
        vo.setApiKeyMasked(config.getKeyHint() == null ? null : "sk-***" + config.getKeyHint());
        vo.setModel(config.getModel());
        vo.setVoice(config.getVoice());
        vo.setStatus(ConfigStatus.fromCode(config.getStatus()));
        vo.setLastCheckedAt(config.getLastCheckedAt());
        vo.setValidateDetail(config.getValidateDetail());
        return vo;
    }
}
