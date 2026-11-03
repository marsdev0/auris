// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author geyan
 * @date 2026/9/12
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "auris.transcribe")
public class TranscribeProperties {

    private int timeoutHours = 2;
}
