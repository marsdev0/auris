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
 * Unified engine call anti-corruption layer: HTTP concerns only — reachability and status-code translation.
 * EngineResp.code expresses "this call's business result"; its meaning and disposition (throw or treat as result)
 * belong to the calling domain. This skeleton does no code checking; the status-code → business-error mapping table is also injected by the calling domain
 *
 * @author geyan
 * @date 2026/10/11
 */
@Slf4j
@Component
public class EngineClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Execute one engine call:
     * unreachable/timeout → 503 ENGINE_UNAVAILABLE; HTTP 4xx/5xx → the calling domain's mapping table;
     * everything else returns EngineResp as-is; code disposition belongs to the caller
     */
    public <T> EngineResp<T> execute(Supplier<EngineResp<T>> call,
                                     Function<RestClientResponseException, AurisException> httpErrorMapping) {
        EngineResp<T> result;
        try {
            result = call.get();
        } catch (ResourceAccessException e) {
            // Unreachable/timeout (engine down, network cut): fail fast, 503
            log.error("engine 不可达: {}", e.getMessage());
            throw new AurisException(AIErrorCode.ENGINE_UNAVAILABLE);
        } catch (RestClientResponseException e) {
            log.error("engine 异常状态 {}: {}", e.getStatusCode().value(), extractDetail(e));
            throw httpErrorMapping.apply(e);
        }
        return result;
    }

    /**
     * Extract message from the engine's error response (FastAPI HTTPException body looks like {"detail": "..."})
     */
    public String extractDetail(RestClientResponseException e) {
        try {
            JsonNode node = MAPPER.readTree(e.getResponseBodyAsString());
            if (node.hasNonNull("detail")) {
                return node.get("detail").asText();
            }
        } catch (Exception ignored) {
            // Body is not JSON (or unreadable): fall back to the raw body; null if nothing extractable
        }
        String body = e.getResponseBodyAsString();
        return body.isBlank() ? null : body;
    }
}
