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
    AUDIO_INVALID(400, 400_101, "音频内容为空或解码失败"),
    TASK_NOT_FOUND(404, 404_101, "任务不存在或已过期"),
    ENGINE_ERROR(502, 502_101, "转写引擎内部错误"),
    ENGINE_UNAVAILABLE(503, 503_101, "转写引擎暂不可用, 请稍后重试");;

    private final Integer httpStatus;

    private final Integer code;

    private final String msg;
}
