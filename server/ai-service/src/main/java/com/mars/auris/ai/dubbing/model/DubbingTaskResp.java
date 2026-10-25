// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 配音任务对前端的投影(status 转可读字符串,模板比较不用查表)。 */
@Data
public class DubbingTaskResp {
    /** 一律 String(P3 铁律:雪花 id 超 JS 2^53) */
    private String id;
    private String title;
    private String url;
    /** audio|video */
    private String kind;
    /** init|running|completed|failed */
    private String status;
    private Integer stageIndex;
    private Integer stageTotal;
    private Long audioBytes;
    private String error;
    private LocalDateTime createdAt;
}
