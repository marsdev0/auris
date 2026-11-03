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
     * Register JavaTimeModule: LocalDateTime and other time types are unsupported by default;
     * a Kafka message carrying occurred_at was once poisoned by this (exposed in the 2026-09-29 end-to-end first run).
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
