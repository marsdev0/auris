package com.mars.auris.ai.byok.model.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * The data part of engine /byok/validate response.
 * Success/failure is expressed by the outer EngineResp.code (0=passed, non-0=failed, reason in EngineResp.message),
 * this body carries only the extra info on success
 *
 * @author geyan
 * @date 2026/10/11
 */
@Data
public class EngineValidateResp {

    /** Measured latency in ms */
    @JsonProperty("latency_ms")
    private Integer latencyMs;

    /** Success summary (e.g. models listed, 87 available) */
    private String detail;
}
