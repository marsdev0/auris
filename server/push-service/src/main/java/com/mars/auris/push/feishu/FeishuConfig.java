package com.mars.auris.push.feishu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feishu channel rate-limit config (P5 §2.7: Feishu hard cap ~100/min, bucket set to 90 for headroom).
 *
 * @author geyan
 * @date 2026/10/2
 */
@Configuration
public class FeishuConfig {

    /**
     * Burst capacity is 10 seconds' worth (90/min → 1.5/s × 10s = 15): allows short bursts, doesn't bank a whole minute's quota
     */
    @Bean
    public TokenBucket feishuRateLimiter(
            @Value("${auris.push.feishu.rate-per-minute:90}") double ratePerMinute) {
        return new TokenBucket(ratePerMinute / 60.0, Math.max(1, ratePerMinute / 6));
    }
}
