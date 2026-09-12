// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * AI 服务入口：对接 Python engine（ASR/TTS/Agent）。
 *
 * @author geyan
 */
@SpringBootApplication(scanBasePackages = {"com.mars.auris.ai", "com.mars.auris.common.error"})
@EnableScheduling
public class AiApplication {

    /**
     * 全链路 UTC:与 MySQL 容器(UTC)对齐,LocalDateTime.now()/日志时间戳/超时兜底 cutoff
     * 与 created_at 同一世界。展示时区转换是前端的职责(P3 §9 时区 bug 的修复决策)
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
