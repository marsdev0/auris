// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.enums;

import lombok.Getter;

/**
 * @author geyan
 * @date 2026/10/26
 */
@Getter
public enum DubbingTaskStatus {

    /**
     * submitted, engine not yet accepted or just accepted
     */
    INIT(0),

    /**
     * running (pipeline advancing, progress pulled by polling)
     */
    RUNNING(1),

    /**
     * succeeded, artifacts ready to stream
     */
    COMPLETED(2),

    /**
     * failed, error_msg kept for trace
     */
    FAILED(3);

    private final int code;

    DubbingTaskStatus(int code) {
        this.code = code;
    }

    public static boolean isTerminal(int code) {
        return code >= COMPLETED.code;
    }

    public static DubbingTaskStatus of(int code) {
        for (DubbingTaskStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return RUNNING;
    }
}
