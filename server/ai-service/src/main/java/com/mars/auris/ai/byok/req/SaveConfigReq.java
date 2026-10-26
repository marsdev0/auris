// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.req;

import com.mars.auris.ai.byok.enums.ModelDomain;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存 BYOK 配置请求。apiKey 明文仅此一次上行,落库即密文;
 * 域相关规则(openai_compat 必填 baseUrl / llm 必填 model)在 Service 层校验
 *
 * @author geyan
 * @date 2026/10/10
 */
@Data
public class SaveConfigReq {

    /**
     * 模型域,决定落 uk(tenant_id,user_id,domain) 哪个槽位
     */
    @NotNull(message = "domain must not be null")
    private ModelDomain domain;

    /**
     * 供应商:openai_compat / edge_tts / fish_audio / dashscope ...
     */
    @NotBlank(message = "provider must not be blank")
    @Size(max = 64, message = "provider must be at most 64 characters")
    private String provider;

    /**
     * 接入地址,edge_tts 等免配置的传 null
     */
    @Size(max = 512, message = "baseUrl must be at most 512 characters")
    private String baseUrl;

    /**
     * 明文 key:非空 = 换 key/首次填;空 = 沿用旧 key(编辑语义)。
     * 不加 @NotBlank——空是合法业务语义,免 key 配置也是空
     */
    @Size(max = 512, message = "apiKey must be at most 512 characters")
    private String apiKey;

    /**
     * 模型名,必填,Service 校验(该域具体模型标识,如 gpt-4o / qwen3-asr / cosyvoice-v2;
     * edge_tts 等无显式模型的 provider 填 default)
     */
    @Size(max = 128, message = "model must be at most 128 characters")
    private String model;

    /**
     * tts 音色(如 zh-CN-XiaoxiaoNeural);音色参数,与 model 正交,不替代模型
     */
    @Size(max = 128, message = "voice must be at most 128 characters")
    private String voice;
}
