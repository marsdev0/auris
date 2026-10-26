// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 模型域。DB 存小写字符串(llm/tts/asr),API 出小写字符串枚举
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
     * 字符串转枚举(容忍大小写,未知值抛异常拦截脏数据)。
     * 兼任 Jackson 反序列化工厂:前端传 "llm" 直接映射 LLM
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
