// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.error;

import com.mars.auris.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author geyan
 * @date 2026/8/27
 */
@Getter
@AllArgsConstructor
public enum AIErrorCode implements ErrorCode {
    AUDIO_INVALID(400, 400_101, "Audio content is empty or failed to decode"),
    TASK_NOT_FOUND(404, 404_101, "Task not found or expired"),
    RECORD_NOT_FOUND(404, 404_102, "Record not found"),
    ENGINE_ERROR(502, 502_101, "Transcription engine internal error"),
    ENGINE_UNAVAILABLE(503, 503_101, "Transcription engine temporarily unavailable, please retry later");

    private final Integer httpStatus;

    private final Integer code;

    private final String msg;
}
