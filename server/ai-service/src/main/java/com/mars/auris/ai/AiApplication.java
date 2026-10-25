// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * AI service entry: integrates the Python engine (ASR/TTS/Agent).
 *
 * @author geyan
 */
@SpringBootApplication(scanBasePackages = {"com.mars.auris.ai", "com.mars.auris.common.error"})
@EnableScheduling
public class AiApplication {

    /**
 * Full-chain UTC: aligned with the MySQL container (UTC); LocalDateTime.now()/log timestamps/timeout cutoff
 * share the same world as created_at. Display timezone conversion is the frontend's job (P3 §9 timezone bug fix).
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
