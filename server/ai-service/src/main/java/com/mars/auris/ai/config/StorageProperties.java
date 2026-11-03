package com.mars.auris.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * @author geyan
 * @date 2026/11/3
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "auris.storage")
public class StorageProperties {

    private String endpoint;

    private String accessKey;

    private String secretKey;

    private String bucket;

    private String region = "cn-north-1";

    private Duration presignExpire = Duration.ofSeconds(900);
}
