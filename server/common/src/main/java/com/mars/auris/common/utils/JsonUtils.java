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

