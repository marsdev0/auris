// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.model.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * engine /byok/validate 的响应 data 部分。
 * 成败由外层 EngineResp.code 表达(0=校验通过,非 0=校验失败,失败原因在 EngineResp.message),
 * 本体只承载成功时的附加信息
 *
 * @author geyan
 * @date 2026/10/11
 */
@Data
public class EngineValidateResp {

    /** 实测耗时 ms */
    @JsonProperty("latency_ms")
    private Integer latencyMs;

    /** 成功摘要(如 models listed, 87 available) */
    private String detail;
}
