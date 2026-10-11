package com.mars.auris.ai.byok.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/10/10
 */
@Data
@TableName("user_model_config")
public class UserModelConfigDO {

    /**
     * 自增主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户(预留,0=默认/个人;仅列预留,不建隔离逻辑)
     */
    private Long tenantId;

    /**
     * 所属用户
     */
    private Long userId;

    /**
     * 模型域:llm / tts / asr
     */
    private String domain;

    /**
     * 供应商:openai_compat / edge_tts / fish_audio / dashscope ...
     */
    private String provider;

    /**
     * 接入地址(openai_compat 等自建网关用)
     */
    private String baseUrl;

    /**
     * API Key 密文,AES-256-GCM: IV+密文+tag
     */
    private byte[] apiKeyCipher;

    /**
     * 主密钥轮换版本
     */
    private Integer keyVersion;

    /**
     * 掩码尾 4 位
     */
    private String keyHint;

    /**
     * 模型名,各域通用(llm/asr/tts 的具体模型标识)
     */
    private String model;

    /**
     * tts 音色
     */
    private String voice;

    /**
     * 扩展参数 JSON(温度/语速等)
     */
    private String extraJson;

    /**
     * 0-unverified 1-verified 2-failed
     */
    private Integer status;

    /**
     * 最近一次连通性校验时间
     */
    private LocalDateTime lastCheckedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

}
