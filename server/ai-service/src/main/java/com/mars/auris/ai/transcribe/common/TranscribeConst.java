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

    String URL_ASR_TASK_START_URL = "/v1/asr/task/start-url";

    /** Engine task status words, aligned with AsrTaskDTO.status comments */
    String ENGINE_STATUS_TRANSCRIBING = "transcribing";

    String ENGINE_STATUS_COMPLETED = "completed";

    String ENGINE_STATUS_FAILED = "failed";

    /** Record submission entry (transcribe_record.source; the primary semantics is the entry point, sync/async is a per-entry mode) */
    int SOURCE_UPLOAD_ASYNC = 0;   // 文件上传 → 异步任务

    int SOURCE_UPLOAD_SYNC = 1;    // 文件上传 → 同步直返

    int SOURCE_URL = 2;            // URL 链接 → 异步任务
}
