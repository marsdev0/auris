package com.mars.auris.ai.transcribe.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记录列表项。不含 text——列表不查大字段,不触发溢出页 IO
 * 也不含 errorMsg,失败原因走详情接口
 *
 * @author geyan
 * @date 2026/9/12
 */
@Data
public class RecordItemResp {

    /**
     * recordId,字符串承载防 JS 精度丢失(与 SubmitTaskResp/LongTaskResp 对齐)
     */
    private String recordId;

    /**
     * 文件名
     */
    private String title;

    /**
     * transcribing/completed/failed,与轮询响应的状态词对齐
     */
    private String status;

    /**
     * 音频时长(毫秒),completed 时有值
     */
    private Integer durationMs;

    /**
     * 提交时间
     */
    private LocalDateTime createdAt;
}
