package com.mars.auris.push.cache;

import com.mars.auris.push.mapper.NoticeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 未读数:Redis 计数器——不是缓存,是权威计数(P5 §2.13.2)。
 * <p>
 * 未读数是写密集的派生值(每条通知都变),cache-aside 会被写穿;
 * 计数器把"COUNT 全表"变成 O(1) 读写。允许轻微漂移:红点是展示层,
 * 列表是事实层;全部已读时 DEL 重算兜底(见 reset)。
 *
 * @author geyan
 * @date 2026/9/28
 */
@Service
public class UnreadCounter {

    @Autowired
    private NoticeMapper noticeMapper;

    /**
     * key 加 7 天 TTL,防死用户常驻
     */
    private static final Duration TTL = Duration.ofDays(7);

    @Autowired
    private StringRedisTemplate redis;

    private String key(Long userId) {
        return "notify:unread:" + userId;
    }

    /**
     * 落池成功后 +1(EventConsumer 接线)
     */
    public void incr(Long userId) {
        redis.opsForValue().increment(key(userId));
    }

    /**
     * 单条已读后 -1(仅当条件 UPDATE 命中时调用,见 NoticeService.markRead)
     */
    public void decr(Long userId) {
        redis.opsForValue().decrement(key(userId));
    }

    /**
     * 全部已读:直接删除,下次 get 回源 COUNT=0(计数以 DB 为准的兜底路径)
     */
    public void reset(Long userId) {
        redis.delete(key(userId));
    }

    /**
     * 红点查询
     */
    public long get(Long userId) {
        String v = redis.opsForValue().get(key(userId));
        if (v != null) {
            return Long.parseLong(v);
        }
        // 这里没有采用 cache-aside
        long count = noticeMapper.countUnread(userId);
        redis.opsForValue().set(key(userId), String.valueOf(count), TTL);
        return count;
    }
}
