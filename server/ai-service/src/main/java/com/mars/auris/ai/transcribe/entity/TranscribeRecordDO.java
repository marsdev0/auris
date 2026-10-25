package com.mars.auris.ai.transcribe.entity;

import  com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/9/11
 */
@Data
@TableName("transcribe_record")
public class TranscribeRecordDO {

    /**
     * Snowflake ID (external recordId)
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Submitting user, data isolation key
     */
    private Long userId;

    /**
     * Engine task ID (uuid hex), for tracing
     */
    private String engineTaskId;

    /**
     * 0-async or 1-sync
     */
    private Integer source;

    /**
     * Filename (basename of originalFilename at submission)
     */
    private String title;

    /**
     * Duration (derived from segments max(end_ms) on completion)
     */
    private Integer durationMs;

    /**
     * Full transcription text (filled on completion)
     */
    private String text;

    /**
     * Segment details (with timestamps, directly usable for P6 chapter outlines)
     */
    private String segmentsJson;

    /**
     * 0-transcribing 1-completed 2-failed
     */
    private Integer status;

    /**
     * Failure reason (filled on failure, truncated if long)
     */
    private String errorMsg;

    /**
     * Created at
     */
    private LocalDateTime createdAt;

    /**
     * Updated at
     */
    private LocalDateTime updatedAt;

}
