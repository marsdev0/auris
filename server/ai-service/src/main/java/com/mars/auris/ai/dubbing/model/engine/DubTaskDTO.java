// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.model.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** engine GET /v1/dubbing/task/{id} 的 data 投影(to_public 契约,snake_case 映射)。 */
@Data
public class DubTaskDTO {
    /** 0-init 1-running 2-completed 3-failed(与 DO/SQL/engine DubStatus 同口径) */
    private Integer status;
    @JsonProperty("stage_index")
    private Integer stageIndex;
    @JsonProperty("stage_total")
    private Integer stageTotal;
    /** audio|video(engine 是 kind 判定的唯一事实源,轮询校准) */
    private String kind;
    private String title;
    private String error;
    @JsonProperty("audio_bytes")
    private Long audioBytes;
}
