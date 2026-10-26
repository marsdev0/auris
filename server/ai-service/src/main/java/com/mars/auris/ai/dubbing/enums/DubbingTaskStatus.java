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
     * 已发起,engine 未受理或刚受理
     */
    INIT(0),

    /**
     * 执行中(流水线推进,轮询拉进度)
     */
    RUNNING(1),

    /**
     * 成功,产物就绪可拉流
     */
    COMPLETED(2),

    /**
     * 失败,error_msg 留痕
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
