package com.mars.auris.ai.byok.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * BYOK config validation status. DB stores int (consistent with the all-TINYINT discipline), API returns string enum
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
     * DB int to enum; unknown values throw to intercept dirty data
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
