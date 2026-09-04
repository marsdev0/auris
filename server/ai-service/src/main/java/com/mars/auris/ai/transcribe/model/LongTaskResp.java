package com.mars.auris.ai.transcribe.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/9/4
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LongTaskResp {

    private String taskId;

    private String status;

    private double progress;

    private Result result;


    @Data
    private static class Result {

        private String text;
    }
}
