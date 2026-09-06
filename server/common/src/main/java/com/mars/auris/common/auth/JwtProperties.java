package com.mars.auris.common.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author geyan
 * @date 2026/9/6
 */
@ConfigurationProperties(prefix = "auris.jwt")
@Data
public class JwtProperties {

    private String accessTokenSecret;

    private String refreshTokenSecret;

    private Long accessTokenExpire;

    private Long refreshTokenExpire;

    private String issuer;
}
