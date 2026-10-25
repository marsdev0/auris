package com.mars.auris.push.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Data
@TableName("notice")
public class NoticeDO {

    /**
     * Snowflake ID (19 digits).
     * <p>
     * JSON output as String: exceeds JS Number 2^53; numeric form would zero the frontend's tail digits (P3 contract rule 1).
     */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * Submitting user, data isolation key, sharding key
     */
    private Long userId;

    /**
     * Source event ID (uuid), consumption idempotency key (uk_event)
     */
    private String eventId;

    /**
     * Notification type: transcribe.completed/transcribe.failed/transcribe.timeout/alert.raised...
     */
    private String type;

    /**
     * Notification title, e.g. "Transcription complete: 《xxx》", templated at pool-write time
     */
    private String title;

    /**
     * Notification body (one-line summary + recordId, click to navigate)
     */
    private String content;

    /**
     * 0-unread 1-read
     */
    private Integer readFlag;

    /**
     * Created at (UTC, list sort key)
     */
    private LocalDateTime createdAt;

}
