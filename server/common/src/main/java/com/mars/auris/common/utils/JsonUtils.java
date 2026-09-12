package com.mars.auris.common.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * @author geyan
 * @date 2026/9/12
 */
@Slf4j
public final class JsonUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("序列化失败 ", e);
        }
        return null;
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("反序列化失败 ", e);
        }
        return null;
    }
}

