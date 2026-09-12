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

    private double progress;

    private Result result;

    /**
     * 失败原因(failed 时有值)
     */
    private String errorMsg;


    @Data
    public static class Result {

        private String text;
    }
}
