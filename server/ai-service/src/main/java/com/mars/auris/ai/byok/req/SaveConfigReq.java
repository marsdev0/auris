// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.req;

import com.mars.auris.ai.byok.enums.ModelDomain;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Save BYOK config request. apiKey plaintext goes up exactly once, stored as ciphertext;
 * domain-specific rules (openai_compat requires baseUrl / llm requires model) validated in the Service layer
 *
 * @author geyan
 * @date 2026/10/10
 */
@Data
public class SaveConfigReq {

    /**
     * Model domain, decides which uk(tenant_id,user_id,domain) slot this lands in
     */
    @NotNull(message = "domain must not be null")
    private ModelDomain domain;

    /**
     * Provider: openai_compat / edge_tts / fish_audio / dashscope ...
     */
    @NotBlank(message = "provider must not be blank")
    @Size(max = 64, message = "provider must be at most 64 characters")
    private String provider;

    /**
     * Endpoint URL, null for key-free providers like edge_tts
     */
    @Size(max = 512, message = "baseUrl must be at most 512 characters")
    private String baseUrl;

    /**
     * Plaintext key: non-empty = replace/first-time; empty = keep old key (edit semantics).
     * No @NotBlank — empty is a valid business semantic, key-free configs are also empty
     */
    @Size(max = 512, message = "apiKey must be at most 512 characters")
    private String apiKey;

    /**
     * Model name, required, validated in Service (concrete model id, e.g. gpt-4o / qwen3-asr / cosyvoice-v2;
     * providers without an explicit model like edge_tts fill in default)
     */
    @Size(max = 128, message = "model must be at most 128 characters")
    private String model;

    /**
     * TTS voice (e.g. zh-CN-XiaoxiaoNeural); a voice param, orthogonal to model, does not replace it
     */
    @Size(max = 128, message = "voice must be at most 128 characters")
    private String voice;
}
