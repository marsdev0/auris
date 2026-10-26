// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * BYOK 配置校验状态。DB 落 int(与全库 TINYINT 纪律一致),API 出字符串枚举
 *
 * @author geyan
 * @date 2026/10/10
 */
@Getter
public enum ConfigStatus {

    UNVERIFIED(0, "unverified"),
    VERIFIED(1, "verified"),
    FAILED(2, "failed");

    private final int code;

    @JsonValue
    private final String value;

    ConfigStatus(int code, String value) {
        this.code = code;
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * DB int 转枚举,未知值抛异常拦截脏数据
     */
    public static ConfigStatus fromCode(int code) {
        for (ConfigStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown config status code: " + code);
    }
}
