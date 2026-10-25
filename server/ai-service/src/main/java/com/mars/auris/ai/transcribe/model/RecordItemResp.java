package com.mars.auris.ai.transcribe.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Record list item. No text — list queries skip big fields, no overflow-page IO
 * No errorMsg either; failure reasons go through the detail endpoint
 *
 * @author geyan
 * @date 2026/9/12
 */
@Data
public class RecordItemResp {

    /**
     * recordId, carried as string to prevent JS precision loss (aligned with SubmitTaskResp/LongTaskResp)
     */
    private String recordId;

    /**
     * Filename
     */
    private String title;

    /**
     * transcribing/completed/failed, aligned with polling response status words
     */
    private String status;

    /**
     * Audio duration (ms), present on completed
     */
    private Integer durationMs;

    /**
     * Submitted at
     */
    private LocalDateTime createdAt;
}
