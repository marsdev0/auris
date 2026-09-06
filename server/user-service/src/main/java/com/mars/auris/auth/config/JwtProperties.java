package com.mars.auris.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Configuration
@ConfigurationProperties(prefix = "auris.jwt")
@Data
public class JwtProperties {

    private String accessTokenSecret;

    private String refreshTokenSecret;

    private Long accessTokenExpire;

    private Long refreshTokenExpire;

    private String issuer;
}
