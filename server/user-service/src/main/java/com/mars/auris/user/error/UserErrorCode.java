// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
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
    LOGIN_FAILED(401, 401_201, "Invalid username or password"),
    USER_PASSWORD_ERROR(401, 401_202, "Invalid password"),
    REFRESH_TOKEN_INVALID(401, 401_203, "refreshToken is invalid or expired"),
    ACCOUNT_DISABLED(403, 403_201, "Account has been disabled"),
    USER_NOT_FOUND(404, 404_201, "User not found"),
    USERNAME_ALREADY_EXISTS(409, 409_201, "Username already exists");

    private final Integer httpStatus;

    private final Integer code;

    private final String msg;
}
