package com.mars.auris.auth.store;

import com.mars.auris.common.auth.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Storage and retrieval of refreshTokens; implementation details (Redis/key format/TTL) are opaque outside
 *
 * @author geyan
 * @date 2026/9/6
 */
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auris:refresh_token:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private JwtProperties jwtProperties;

    /**
     * TTL stays in sync with the refreshToken's own JWT expiry, avoiding early/late invalidation on the storage side after config changes
     */
    public void save(Long userId, String refreshToken) {
        redisTemplate.opsForValue()
                .set(KEY_PREFIX + userId, refreshToken, jwtProperties.getRefreshTokenExpire(), TimeUnit.SECONDS);
    }

    public String get(Long userId) {
        return (String) redisTemplate.opsForValue().get(KEY_PREFIX + userId);
    }

    public void delete(Long userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
    }
}
