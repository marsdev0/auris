package com.mars.auris.push.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mars.auris.push.cache.UnreadCounter;
import com.mars.auris.push.entity.NoticeDO;
import com.mars.auris.push.event.TranscribeEvent;
import com.mars.auris.push.mapper.NoticeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Service
public class NoticeService {

    // Keep is-new and the notification id separate; don't use null to mean new — it would tangle with id semantics
    public record CreateResult(Long id, boolean created) {}

    @Autowired
    private NoticeMapper noticeMapper;
    @Autowired
    private UnreadCounter unreadCounter;

    public CreateResult createIfAbsent(TranscribeEvent event) {
        if (event == null) {
            return null;
        }
        NoticeDO r = new NoticeDO();
        r.setUserId(event.getUserId());
        r.setEventId(event.getEventId());
        r.setType(event.getType());
        r.setTitle(buildTitle(event));
        r.setContent(buildContent(event));
        r.setReadFlag(0);
        try {
            noticeMapper.insert(r);
            return new CreateResult(r.getId(), true);
        } catch (DuplicateKeyException e) {
            NoticeDO exist = noticeMapper.selectOne(new LambdaQueryWrapper<NoticeDO>()
                    .eq(NoticeDO::getEventId, event.getEventId()));
            if (exist == null) {
                // Theoretically unreachable
                return null;
            }
            return new CreateResult(exist.getId(), false);
        }
    }

    /**
     * Title template: transcribe.completed → "Transcription complete: 《xxx》"
     */
    private String buildTitle(TranscribeEvent event) {
        String title = payloadTitle(event);
        String action = switch (event.getType()) {
            case "transcribe.completed" -> "转写完成";
            case "transcribe.failed" -> "转写失败";
            case "transcribe.timeout" -> "转写超时";
            default -> "转写通知";
        };
        return title.isEmpty() ? action : action + ":《" + title + "》";
    }

    /**
     * Body: one-line summary + recordId (for frontend click-through)
     */
    private String buildContent(TranscribeEvent event) {
        String recordId = event.getPayload() != null ? event.getPayload().getOrDefault("record_id", "") : "";
        String summary = switch (event.getType()) {
            case "transcribe.completed" -> "转写已完成,点击查看全文";
            case "transcribe.failed" -> "转写失败,可重新提交";
            case "transcribe.timeout" -> "转写超时,可重新提交";
            default -> "转写状态更新";
        };
        return summary + ",recordId=" + recordId;
    }

    /**
     * The title inside payload (producer already null-guards to empty string; one more layer against missing key here)
     */
    private String payloadTitle(TranscribeEvent event) {
        return event.getPayload() != null ? event.getPayload().getOrDefault("title", "") : "";
    }

    /**
     * My notifications, newest first (read pattern: P5 §2.8 pulls the latest 50, user_id+created_at index)
     */
    public List<NoticeDO> listByUser(Long userId, int limit) {
        return noticeMapper.selectList(new QueryWrapper<NoticeDO>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT " + limit));
    }

    /**
     * Mark single read: DECR only when the conditional UPDATE hits (repeated clicks are no-ops, the count can't go below zero)
     */
    public boolean markRead(Long userId, Long noticeId) {
        if (noticeMapper.markRead(noticeId, userId) == 1) {
            unreadCounter.decr(userId);
            return true;
        }
        return false;
    }

    /**
     * Mark all read: n hits → whole counter reset (next get falls back to COUNT=0)
     */
    public int markAllRead(Long userId) {
        int updated = noticeMapper.markAllRead(userId);
        if (updated > 0) {
            unreadCounter.reset(userId);
        }
        return updated;
    }
}
