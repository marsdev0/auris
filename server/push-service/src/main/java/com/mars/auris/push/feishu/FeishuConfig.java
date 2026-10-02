package com.mars.auris.push.feishu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书渠道的限速配置(P5 §2.7:飞书硬限 ~100/min,桶设 90 留余量)。
 *
 * @author geyan
 * @date 2026/10/2
 */
@Configuration
public class FeishuConfig {

    /**
     * 突发容量取 10 秒的量(90/min → 1.5/s × 10s = 15):允许短突发,不囤整分钟额度
     */
    @Bean
    public TokenBucket feishuRateLimiter(
            @Value("${auris.push.feishu.rate-per-minute:90}") double ratePerMinute) {
        return new TokenBucket(ratePerMinute / 60.0, Math.max(1, ratePerMinute / 6));
    }
}
