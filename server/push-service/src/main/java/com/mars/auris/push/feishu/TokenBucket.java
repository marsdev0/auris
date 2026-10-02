package com.mars.auris.push.feishu;

import java.util.concurrent.TimeUnit;

/**
 * 单机阻塞式令牌桶(飞书限速 90/min,P5 §2.7 渠道出口阀)。
 * <p>
 * 选阻塞而非拒绝:拿不到令牌就等——洪峰排队在 Kafka topic(lag 可观测),不误伤 attempt
 * (tryAcquire 失败若走 markRetry,洪峰会把 attempt 快速烧到 5 误判死)。
 * 单机实现即够:Stage 1 单实例;多实例时各持一份桶,总速率 ≈ N×rate,届时按实例数配分。
 * <p>
 * nanoTime 单调钟(不受系统改时间影响);synchronized + wait 分段等待防时钟漂移。
 *
 * @author geyan
 * @date 2026/10/2
 */
public class TokenBucket {

    /**
     * 桶容量(允许的突发量)
     */
    private final double capacity;
    /**
     * 每纳秒补充的令牌数
     */
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double permitsPerSecond, double burst) {
        this.capacity = burst;
        this.tokensPerNano = permitsPerSecond / TimeUnit.SECONDS.toNanos(1);
        this.tokens = burst;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 阻塞直到取到一枚令牌;被中断抛 InterruptedException(交给 handle 转 false 语义)
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return;
            }
            long missingNanos = (long) ((1.0 - tokens) / tokensPerNano);
            long ms = Math.max(1, TimeUnit.NANOSECONDS.toMillis(missingNanos));
            wait(Math.min(ms, 1_000));   // 分段等待,每次最多睡 1s
        }
    }

    private void refill() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * tokensPerNano);
        lastRefillNanos = now;
    }
}
