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
     * Auto-increment primary key
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Tenant (reserved, 0=default/personal; column reserved only, no isolation logic)
     */
    private Long tenantId;

    /**
     * Owning user
     */
    private Long userId;

    /**
     * Model domain: llm / tts / asr
     */
    private String domain;

    /**
     * Provider: openai_compat / edge_tts / fish_audio / dashscope ...
     */
    private String provider;

    /**
     * Endpoint URL (for self-hosted gateways like openai_compat)
     */
    private String baseUrl;

    /**
     * API Key ciphertext, AES-256-GCM: IV+ciphertext+tag
     */
    private byte[] apiKeyCipher;

    /**
     * Master key rotation version
     */
    private Integer keyVersion;

    /**
     * Masked last 4 chars
     */
    private String keyHint;

    /**
     * Model name, generic across domains (concrete model id for llm/asr/tts)
     */
    private String model;

    /**
     * TTS voice
     */
    private String voice;

    /**
     * Extra params JSON (temperature/speech rate etc.)
     */
    private String extraJson;

    /**
     * 0-unverified 1-verified 2-failed
     */
    private Integer status;

    /**
     * Last connectivity check time
     */
    private LocalDateTime lastCheckedAt;

    /**
     * Last check result (failure reason / success summary + latency)
     */
    private String validateDetail;

    /**
     * Created at
     */
    private LocalDateTime createdAt;

    /**
     * Updated at
     */
    private LocalDateTime updatedAt;

}
