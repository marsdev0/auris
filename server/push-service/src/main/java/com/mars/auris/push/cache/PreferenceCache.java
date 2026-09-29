package com.mars.auris.push.cache;

import com.mars.auris.push.mapper.UserNoticePreferenceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * @author geyan
 * @date 2026/9/16
 */
@Component
public class PreferenceCache {

    @Autowired
    private RedisTemplate<String, String> redis;
    @Autowired
    private UserNoticePreferenceMapper userNoticePreferenceMapper;

    public int maskOf(Long userId, String type) {
        String key = "auris:notify:pref:" + userId + ":" + type;
        String hit = redis.opsForValue().get(key);
        if (hit != null) {
            return Integer.parseInt(hit);
        }
        int chMask = userNoticePreferenceMapper.selectMask(userId, type);
        redis.opsForValue().set(key, String.valueOf(chMask), Duration.ofMinutes(5));
        return chMask;
    }
}
