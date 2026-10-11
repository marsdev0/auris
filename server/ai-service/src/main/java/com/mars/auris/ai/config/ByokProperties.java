// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * BYOK 密钥治理配置
 *
 * @author geyan
 * @date 2026/10/10
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "auris.byok")
public class ByokProperties {

    /**
     * AES-256 主密钥,Base64 编码的 32 字节;仅用于加解密用户 API Key,禁止外泄
     */
    private String masterKey;

    /**
     * 主密钥版本号,与 masterKey 配套落库 key_version;
     * 轮换时:新密钥+版本号递增,旧记录按旧版本密文解密迁移
     */
    private int keyVersion = 1;
}
