// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Dubbing task frontend projection (status as readable string, no lookup table needed in templates). */
@Data
public class DubbingTaskResp {
    /** Always String (P3 rule: snowflake ids exceed JS 2^53) */
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
