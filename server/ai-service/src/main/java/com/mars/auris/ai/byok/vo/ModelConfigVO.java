package com.mars.auris.ai.byok.vo;

import com.mars.auris.ai.byok.enums.ConfigStatus;
import com.mars.auris.ai.byok.enums.ModelDomain;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * BYOK 配置回显 VO。密钥治理:只回掩码,密文/明文均不出库
 *
 * @author geyan
 * @date 2026/10/10
 */
@Data
public class ModelConfigVO {

    /**
     * 模型域
     */
    private ModelDomain domain;

    /**
     * 供应商:openai_compat / edge_tts / fish_audio / dashscope ...
     */
    private String provider;

    /**
     * 接入地址,edge_tts 等免配置的为 null
     */
    private String baseUrl;

    /**
     * 掩码回显:sk-*** + 尾4位(由 key_hint 拼出),免 key 配置为 null
     */
    private String apiKeyMasked;

    /**
     * 模型名
     */
    private String model;

    /**
     * tts 音色
     */
    private String voice;

    /**
     * 校验状态
     */
    private ConfigStatus status;

    /**
     * 最近一次连通性校验时间(UTC,无后缀 ISO 字符串)
     */
    private LocalDateTime lastCheckedAt;

    /**
     * 最近一次校验结果:成功为"连通正常 · xxxms",失败为原因(超长截断)
     */
    private String validateDetail;
}
