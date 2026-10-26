// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.enums;

import lombok.Getter;

/**
 * @author geyan
 * @date 2026/10/26
 */
@Getter
public enum DubbingKind {

    /**
     * 音频链路:下载→分离→ASR→翻译→TTS→混流,共 6 stage
     */
    AUDIO("audio", 6),

    /**
     * 视频链路:多下视频流/画面对齐等,共 8 stage
     */
    VIDEO("video", 8);

    private final String code;

    private final int stageTotal;

    DubbingKind(String code, int stageTotal) {
        this.code = code;
        this.stageTotal = stageTotal;
    }
}
