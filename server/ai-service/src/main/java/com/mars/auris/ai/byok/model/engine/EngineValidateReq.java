package com.mars.auris.ai.byok.model.engine;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Request body forwarded to engine /byok/validate.
 * api_key is the decrypted plaintext, inner-network hop only, zero logging on the engine side
 *
 * <p>Engine channel is snake_case throughout (same discipline as EngineValidateResp.latency_ms):
 * class-level @JsonNaming translates uniformly, baseUrl -> base_url / apiKey -> api_key,
 * new fields auto-adapt, no per-field @JsonProperty needed.
 *
 * @author geyan
 * @date 2026/10/11
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EngineValidateReq {

    /** Model domain: llm / tts / asr */
    private String domain;

    /** Provider, determines which provider the engine constructs */
    private String provider;

    /** Endpoint URL, null = use the provider's official default */
    private String baseUrl;

    /** Plaintext key, null for key-free configs (e.g. edge_tts) */
    private String apiKey;

    /** Model name, null for configs with no model concept */
    private String model;

    /** TTS voice, null for non-tts domains */
    private String voice;
}
