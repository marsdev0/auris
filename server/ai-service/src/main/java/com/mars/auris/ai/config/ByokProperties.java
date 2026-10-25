// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * BYOK key governance config
 *
 * @author geyan
 * @date 2026/10/10
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "auris.byok")
public class ByokProperties {

    /**
     * AES-256 master key, Base64-encoded 32 bytes; used only to encrypt/decrypt user API Keys, must never leak
     */
    private String masterKey;

    /**
     * Master key version, stored alongside as key_version;
     * on rotation: new key + incremented version; old records decrypted with the old version's key
     */
    private int keyVersion = 1;
}
