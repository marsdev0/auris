// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.auris.ai.error.AIErrorCode;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.common.error.AurisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * engine 调用统一防腐层:仅管 HTTP 层——可达性与状态码翻译。
 * EngineResp.code 表达"本次业务结果",含义与处置(抛异常 or 当业务结果)由调用域决定,
 * 本骨架不做 code 检查;状态码→业务错误的映射表也由调用域注入
 *
 * @author geyan
 * @date 2026/10/11
 */
@Slf4j
@Component
public class EngineClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 执行一次 engine 调用:
     * 连不上/超时 → 503 ENGINE_UNAVAILABLE;HTTP 4xx/5xx → 调用域的映射表;
     * 其余原样返回 EngineResp,code 处置权在调用方
     */
    public <T> EngineResp<T> execute(Supplier<EngineResp<T>> call,
                                     Function<RestClientResponseException, AurisException> httpErrorMapping) {
        EngineResp<T> result;
        try {
            result = call.get();
        } catch (ResourceAccessException e) {
            // 连不上/超时(engine 停机、网络断):快速失败,503
            log.error("engine 不可达: {}", e.getMessage());
            throw new AurisException(AIErrorCode.ENGINE_UNAVAILABLE);
        } catch (RestClientResponseException e) {
            log.error("engine 异常状态 {}: {}", e.getStatusCode().value(), extractDetail(e));
            throw httpErrorMapping.apply(e);
        }
        return result;
    }

    /**
     * 从 engine 的错误响应里抠 message(FastAPI HTTPException 的 body 形如 {"detail": "..."} )
     */
    public String extractDetail(RestClientResponseException e) {
        try {
            JsonNode node = MAPPER.readTree(e.getResponseBodyAsString());
            if (node.hasNonNull("detail")) {
                return node.get("detail").asText();
            }
        } catch (Exception ignored) {
            // body 不是 JSON(或读不出来):兜底用原始 body,抠不出来就 null
        }
        String body = e.getResponseBodyAsString();
        return body.isBlank() ? null : body;
    }
}
