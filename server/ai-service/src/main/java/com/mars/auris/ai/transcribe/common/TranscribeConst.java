// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.common;

import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.function.Function;

/**
 * @author geyan
 * @date 2026/8/29
 */
public interface TranscribeConst {

    String URL_ASR_TRANSCRIBE = "/v1/asr/transcribe";

    String URL_ASR_TASK_START = "/v1/asr/task/start";

    String URL_ASR_TASK_GET = "/v1/asr/task/";

    String URL_ASR_TASK_START_URL = "/v1/asr/task/start-url";

    /** engine 任务状态词,与 AsrTaskDTO.status 注释对齐 */
    String ENGINE_STATUS_TRANSCRIBING = "transcribing";

    String ENGINE_STATUS_COMPLETED = "completed";

    String ENGINE_STATUS_FAILED = "failed";

    /** record 提交入口(transcribe_record.source,主导语义是入口,同步异步是各入口的附带模式) */
    int SOURCE_UPLOAD_ASYNC = 0;   // 文件上传 → 异步任务

    int SOURCE_UPLOAD_SYNC = 1;    // 文件上传 → 同步直返

    int SOURCE_URL = 2;            // URL 链接 → 异步任务
}
