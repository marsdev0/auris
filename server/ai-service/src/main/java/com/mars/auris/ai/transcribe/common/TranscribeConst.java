// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.common;

/**
 * @author geyan
 * @date 2026/8/29
 */
public interface TranscribeConst {

    String URL_ASR_TRANSCRIBE = "/v1/asr/transcribe";

    String URL_ASR_TASK_START = "/v1/asr/task/start";

    String URL_ASR_TASK_GET = "/v1/asr/task/";

    /** engine 任务状态词,与 AsrTaskDTO.status 注释对齐 */
    String ENGINE_STATUS_COMPLETED = "completed";

    String ENGINE_STATUS_FAILED = "failed";
}
