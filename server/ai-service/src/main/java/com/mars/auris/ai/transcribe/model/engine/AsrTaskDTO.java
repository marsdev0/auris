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

    /**
     * pending
     * downloading
     * decoding
     * segmenting
     * transcribing
     * completed
     * failed
     */
    private String status;

    private double progress;

    private Result result;

    private String title;

    /**
     * engine 任务失败原因(failed 时有值,回填 record.error_msg 的正主来源)
     */
    private String error;

    @Data
    public static class Result {

        private String text;
    }
}
