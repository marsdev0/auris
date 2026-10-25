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

    private String recordId;

    private String status;

    /**
     * Episode/video title (populated after URL download completes, visible during polling)
     */
    private String title;

    private double progress;

    private Result result;

    /**
     * Failure reason (present on failure)
     */
    private String errorMsg;


    @Data
    public static class Result {

        private String text;
    }
}
