// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Dubbing task (D6): audio/video dual-form; MySQL is the authoritative task state (engine is an execution detail). */
@Data
@TableName("dubbing_task")
public class DubbingTaskDO {

    /** Snowflake ID (external taskId) */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Submitting user, data isolation key */
    private Long userId;

    /** Engine task ID (uuid hex), for tracing */
    private String engineTaskId;

    /** Source URL */
    private String url;

    /** Display name (backfilled after download completes) */
    private String title;

    /** audio|video (D5 dual-form) */
    private String kind;

    /** 0-init 1-running 2-completed 3-failed */
    private Integer status;

    /** Current stage (0-based) */
    private Integer stageIndex;

    /** Stage total (audio 6 / video 8, calibrated from the engine response on first poll) */
    private Integer stageTotal;

    /** Artifact size (filled on completion; mp4 size for video tasks) */
    private Long audioBytes;

    /** Failure reason (truncated if long) */
    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
