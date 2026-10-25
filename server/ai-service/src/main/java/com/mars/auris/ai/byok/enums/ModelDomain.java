package com.mars.auris.ai.byok.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Model domain. DB stores lowercase string (llm/tts/asr), API returns lowercase string enum
 *
 * @author geyan
 * @date 2026/10/10
 */
@Getter
public enum ModelDomain {

    LLM("llm"),
    TTS("tts"),
    ASR("asr");

    @JsonValue
    private final String value;

    ModelDomain(String value) {
        this.value = value;
    }

    /**
     * String to enum (case-tolerant, unknown values throw to intercept dirty data).
     * Doubles as the Jackson deserialization factory: frontend "llm" maps directly to LLM
     */
    @JsonCreator
    public static ModelDomain of(String value) {
        for (ModelDomain domain : values()) {
            if (domain.value.equalsIgnoreCase(value)) {
                return domain;
            }
        }
        throw new IllegalArgumentException("unknown model domain: " + value);
    }
}
