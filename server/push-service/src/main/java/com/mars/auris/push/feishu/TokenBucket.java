package com.mars.auris.push.feishu;

import java.util.concurrent.TimeUnit;

/**
 * Single-machine blocking token bucket (Feishu rate limit 90/min, P5 §2.7 channel-outlet valve).
 * <p>
 * Blocking over rejecting: no token → wait — flood peaks queue in the Kafka topic (lag observable); attempt is not punished
 * (if tryAcquire failure went to markRetry, a flood would burn attempt to 5 quickly and falsely pronounce death).
 * Single-machine implementation suffices: Stage 1 is single-instance; multi-instance gives each a bucket, total rate ≈ N×rate, split the config by instance count then.
 * <p>
 * nanoTime monotonic clock (immune to system clock changes); synchronized + wait with segmented waits guards against clock drift.
 *
 * @author geyan
 * @date 2026/10/2
 */
public class TokenBucket {

    /**
     * Bucket capacity (allowed burst size)
     */
    private final double capacity;
    /**
     * Tokens replenished per nanosecond
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
     * Block until a token is acquired; interruption throws InterruptedException (handle converts to false semantics)
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
