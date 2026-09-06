package com.mars.auris.user.error;

import com.mars.auris.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 服务位: 2 (user-service)
 * code 规则: {httpStatus}_2{序号}
 *
 * @author geyan
 * @date 2026/9/6
 */
@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCode {
    LOGIN_FAILED(401, 401_201, "用户名或密码错误"),
    USER_PASSWORD_ERROR(401, 401_202, "密码错误"),
    REFRESH_TOKEN_INVALID(401, 401_203, "refreshToken无效或已过期"),
    ACCOUNT_DISABLED(403, 403_201, "账号已被禁用"),
    USER_NOT_FOUND(404, 404_201, "用户不存在"),
    USERNAME_ALREADY_EXISTS(409, 409_201, "用户名已存在");

    private final Integer httpStatus;

    private final Integer code;

    private final String msg;
}
