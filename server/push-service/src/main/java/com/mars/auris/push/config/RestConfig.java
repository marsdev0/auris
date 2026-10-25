package com.mars.auris.push.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Channel-outbound HTTP client (Feishu webhook; Email SMTP does not go through this channel)
 *
 * @author geyan
 * @date 2026/10/2
 */
@Configuration
public class RestConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }
}
