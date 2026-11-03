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
     * Audio pipeline: download -> demux -> ASR -> translate -> TTS -> mux, 6 stages in total
     */
    AUDIO("audio", 6),

    /**
     * Video pipeline: extra video download / alignment etc., 8 stages in total
     */
    VIDEO("video", 8);

    private final String code;

    private final int stageTotal;

    DubbingKind(String code, int stageTotal) {
        this.code = code;
        this.stageTotal = stageTotal;
    }
}
