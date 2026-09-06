package com.mars.auris.auth.store;

import com.mars.auris.common.auth.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * refreshToken 的存取，实现细节(Redis/key格式/TTL)对外透明
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
     * TTL 与 refreshToken 自身的 JWT 过期时间保持同步, 避免改配置后存储侧提前/滞后失效
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
