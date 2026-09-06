package com.mars.auris.common.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class AuthConfiguration {

    @Bean
    public JwtService jwtService(JwtProperties jwtProperties) {
        return new JwtService(jwtProperties);
    }
}
