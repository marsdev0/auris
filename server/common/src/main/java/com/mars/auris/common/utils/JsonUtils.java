// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.common.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * @author geyan
 * @date 2026/9/12
 */
@Slf4j
public final class JsonUtils {

    /**
     * 注册 JavaTimeModule:LocalDateTime 等时间类型默认不支持,
     * Kafka 事件里带 occurred_at 的消息曾因此整条毒丸(2026-09-29 端到端首跑暴露)。
     */
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Serialization failed: ", e);
        }
        return null;
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Deserialization failed: ", e);
        }
        return null;
    }
}
