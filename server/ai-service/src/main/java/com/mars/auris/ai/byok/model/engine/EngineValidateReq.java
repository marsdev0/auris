// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.model.engine;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 转发 engine /byok/validate 的请求体。
 * api_key 为解密后明文,仅内网一程,engine 侧零日志
 *
 * <p>engine 通道一律 snake_case(与 EngineValidateResp.latency_ms 同纪律):
 * 类级 @JsonNaming 统一翻译,baseUrl -> base_url / apiKey -> api_key,
 * 新增字段自动适配,无需逐字段 @JsonProperty。
 *
 * @author geyan
 * @date 2026/10/11
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EngineValidateReq {

    /** 模型域:llm / tts / asr */
    private String domain;

    /** 供应商,决定 engine 构造哪个 provider */
    private String provider;

    /** 接入地址,null = 用 provider 官方默认端点 */
    private String baseUrl;

    /** 明文 key,免 key 配置(如 edge_tts)为 null */
    private String apiKey;

    /** 模型名,无模型概念的配置为 null */
    private String model;

    /** tts 音色,非 tts 域为 null */
    private String voice;
}
