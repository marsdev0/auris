package com.mars.auris.common.error;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author geyan
 * @date 2026/8/27
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    OK(200, 200_000, "success"),
    BAD_REQUEST(400, 400_001, "请求参数错误"),
    UNAUTHORIZED(401, 401_001, "未登录或凭证已失效"),
    FORBIDDEN(403, 403_001, "无权访问"),
    NOT_FOUND(404, 404_001, "资源不存在"),
    FILE_TOO_LARGE(413, 413_001, "文件超过上限"),
    INTERNAL_ERROR(500, 500_001, "服务内部错误");;

    private final Integer httpStatus;

    private final Integer code;

    private final String msg;
}
