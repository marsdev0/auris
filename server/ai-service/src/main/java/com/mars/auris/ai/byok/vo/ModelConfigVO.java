package com.mars.auris.ai.byok.vo;

import com.mars.auris.ai.byok.enums.ConfigStatus;
import com.mars.auris.ai.byok.enums.ModelDomain;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * BYOK config echo VO. Key governance: mask only, neither ciphertext nor plaintext leaves the DB
 *
 * @author geyan
 * @date 2026/10/10
 */
@Data
public class ModelConfigVO {

    /**
     * Model domain
     */
    private ModelDomain domain;

    /**
     * Provider: openai_compat / edge_tts / fish_audio / dashscope ...
     */
    private String provider;

    /**
     * Endpoint URL, null for key-free configs like edge_tts
     */
    private String baseUrl;

    /**
     * Masked echo: sk-*** + last 4 (assembled from key_hint), null for key-free configs
     */
    private String apiKeyMasked;

    /**
     * Model name
     */
    private String model;

    /**
     * TTS voice
     */
    private String voice;

    /**
     * Validation status
     */
    private ConfigStatus status;

    /**
     * Last connectivity check time (UTC, suffix-less ISO string)
     */
    private LocalDateTime lastCheckedAt;

    /**
     * Last check result: success is "connected · xxxms", failure is the reason (truncated if long)
     */
    private String validateDetail;
}
