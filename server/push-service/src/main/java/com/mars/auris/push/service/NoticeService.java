// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
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

    // 把是否新建与通知id分开，不要用null来判断是否新建，这个与id混到一起了
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
                // 理论不可达
                return null;
            }
            return new CreateResult(exist.getId(), false);
        }
    }

    /**
     * 标题模板:transcribe.completed → 转写完成:《xxx》
     */
    private String buildTitle(TranscribeEvent event) {
        String title = payloadTitle(event);
        String action = switch (event.getType()) {
            case "transcribe.completed" -> "Transcription completed";
            case "transcribe.failed" -> "Transcription failed";
            case "transcribe.timeout" -> "Transcription timed out";
            default -> "Transcription notice";
        };
        return title.isEmpty() ? action : action + ": " + title;
    }

    /**
     * 正文:一句话摘要 + recordId(前端点击跳转用)
     */
    private String buildContent(TranscribeEvent event) {
        String recordId = event.getPayload() != null ? event.getPayload().getOrDefault("record_id", "") : "";
        String summary = switch (event.getType()) {
            case "transcribe.completed" -> "Transcription completed, click to view the full text";
            case "transcribe.failed" -> "Transcription failed, you can resubmit";
            case "transcribe.timeout" -> "Transcription timed out, you can resubmit";
            default -> "Transcription status update";
        };
        return summary + ",recordId=" + recordId;
    }

    /**
     * payload 里的 title(producer 端 null 已兜底为空串,这里再防一层缺 key)
     */
    private String payloadTitle(TranscribeEvent event) {
        return event.getPayload() != null ? event.getPayload().getOrDefault("title", "") : "";
    }

    /**
     * 我的通知,最新在前(读写模式:P5 §2.8 拉最新 50 条,user_id+created_at 索引)
     */
    public List<NoticeDO> listByUser(Long userId, int limit) {
        return noticeMapper.selectList(new QueryWrapper<NoticeDO>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .last("LIMIT " + limit));
    }

    /**
     * 单条已读:条件 UPDATE 命中才 DECR(重复点已是 no-op,计数不会扣穿)
     */
    public boolean markRead(Long userId, Long noticeId) {
        if (noticeMapper.markRead(noticeId, userId) == 1) {
            unreadCounter.decr(userId);
            return true;
        }
        return false;
    }

    /**
     * 全部已读:命中 n 条则计数器整个重置(下次 get 回源 COUNT=0)
     */
    public int markAllRead(Long userId) {
        int updated = noticeMapper.markAllRead(userId);
        if (updated > 0) {
            unreadCounter.reset(userId);
        }
        return updated;
    }
}
