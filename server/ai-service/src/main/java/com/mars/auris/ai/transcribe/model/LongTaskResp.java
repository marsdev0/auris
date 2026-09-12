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
     * 单集/视频标题(URL 链路下载完成后有值,轮询期间随行可见)
     */
    private String title;

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
