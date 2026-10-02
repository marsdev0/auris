package com.mars.auris.push.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 渠道出站 HTTP 客户端(飞书 webhook;Email SMTP 不走此通道)
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
