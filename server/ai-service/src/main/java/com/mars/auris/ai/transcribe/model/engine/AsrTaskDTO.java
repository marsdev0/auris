package com.mars.auris.ai.transcribe.model.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * @author geyan
 * @date 2026/9/4
 */
@Data
public class AsrTaskDTO {

    @JsonProperty("task_id")
    private String taskId;

    private String status;

    private double progress;

    private Result result;


    @Data
    private static class Result {

        private String text;
    }
}
