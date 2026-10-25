// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.model.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Projection of engine GET /v1/dubbing/task/{id} data (to_public contract, snake_case mapping). */
@Data
public class DubTaskDTO {
    /** 0-init 1-running 2-completed 3-failed (same vocabulary as DO/SQL/engine DubStatus) */
    private Integer status;
    @JsonProperty("stage_index")
    private Integer stageIndex;
    @JsonProperty("stage_total")
    private Integer stageTotal;
    /** audio|video (engine is the single source of truth for kind, calibrated on poll) */
    private String kind;
    private String title;
    private String error;
    @JsonProperty("audio_bytes")
    private Long audioBytes;
}
