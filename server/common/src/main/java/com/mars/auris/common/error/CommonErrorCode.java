// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
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

    BAD_REQUEST(400, 400_001, "Invalid request parameters"),
    UNAUTHORIZED(401, 401_001, "Not signed in or credentials expired"),
    FORBIDDEN(403, 403_001, "Access denied"),
    NOT_FOUND(404, 404_001, "Resource not found"),
    FILE_TOO_LARGE(413, 413_001, "File exceeds size limit"),

    INTERNAL_ERROR(500, 500_001, "Internal server error"),
    SERIALIZE_ERROR(500, 500_002, "Serialization failed"),
    DESERIALIZE_ERROR(500, 500_003, "Deserialization failed");

    private final Integer httpStatus;

    private final Integer code;

    private final String msg;
}
