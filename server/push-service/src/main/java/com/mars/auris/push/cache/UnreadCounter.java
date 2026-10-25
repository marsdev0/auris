package com.mars.auris.push.cache;

import com.mars.auris.push.mapper.NoticeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Unread count: a Redis counter — not a cache, the authoritative count (P5 §2.13.2).
 * <p>
 * Unread count is a write-heavy derived value (changes with every notification); cache-aside would be written through;
 * a counter turns "COUNT the whole table" into O(1) read/write. Slight drift allowed: the red dot is display tier,
 * the list is fact tier; mark-all-read does DEL + recompute as the backstop (see reset).
 *
 * @author geyan
 * @date 2026/9/28
 */
@Service
public class UnreadCounter {

    @Autowired
    private NoticeMapper noticeMapper;

    /**
     * 7-day TTL on keys, prevents dead users from squatting
     */
    private static final Duration TTL = Duration.ofDays(7);

    @Autowired
    private StringRedisTemplate redis;

    private String key(Long userId) {
        return "notify:unread:" + userId;
    }

    /**
     * +1 after successfully landing in the pool (wired by EventConsumer)
     */
    public void incr(Long userId) {
        redis.opsForValue().increment(key(userId));
    }

    /**
     * -1 after single mark-read (called only when the conditional UPDATE hits, see NoticeService.markRead)
     */
    public void decr(Long userId) {
        redis.opsForValue().decrement(key(userId));
    }

    /**
     * Mark all read: just delete; next get falls back to COUNT=0 (the DB-authoritative backstop path)
     */
    public void reset(Long userId) {
        redis.delete(key(userId));
    }

    /**
     * Red-dot query
     */
    public long get(Long userId) {
        String v = redis.opsForValue().get(key(userId));
        if (v != null) {
            return Long.parseLong(v);
        }
        // No cache-aside here
        long count = noticeMapper.countUnread(userId);
        redis.opsForValue().set(key(userId), String.valueOf(count), TTL);
        return count;
    }
}
