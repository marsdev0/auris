// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * @author geyan
 * @date 2026/8/27
 */
@Configuration
public class RestClientConfig {

    @Autowired
    private EngineProperties properties;

    @Bean
    @Qualifier("engineSubmit")
    public RestClient engineSubmitRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(httpComponents(Duration.ofSeconds(30), Duration.ofSeconds(300)))
                .build();
    }

    @Bean
    @Qualifier("enginePoll")
    public RestClient enginePollRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(httpComponents(Duration.ofSeconds(3), Duration.ofSeconds(15)))
                .build();
    }


    private static ClientHttpRequestFactory httpComponents(Duration connect, Duration read) {
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(connect)
                        .withReadTimeout(read));
    }
}
