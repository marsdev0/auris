// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.controller;

import com.mars.auris.ai.transcribe.model.LongTaskResp;
import com.mars.auris.ai.transcribe.model.SubmitTaskResp;
import com.mars.auris.ai.transcribe.model.TranscribeResp;
import com.mars.auris.ai.transcribe.service.TranscribeService;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import com.mars.auris.common.rsp.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * @author geyan
 * @date 2026/8/28
 */
@Slf4j
@RestController
@RequestMapping("/v1/transcribe")
public class TranscribeController {

    @Autowired
    private TranscribeService transcribeService;


    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TranscribeResp> transcribe(@RequestParam("audio") MultipartFile audio,
                                                  @RequestParam(value = "provider", required = false) String provider) {
        try {
            byte[] bytes = audio.getBytes();
            return ApiResponse.ok(transcribeService.transcribeSync(bytes, provider));
        } catch (IOException e) {
            log.error("transcribe error ", e);
            throw new AurisException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    @PostMapping("/task/start")
    public ApiResponse<SubmitTaskResp> submitTask(@RequestParam("audio") MultipartFile audio,
                                                  @RequestParam(value = "provider", required = false) String provider) {
        try {
            byte[] bytes = audio.getBytes();
            return ApiResponse.ok(transcribeService.submitTask(bytes, provider));
        } catch (IOException e) {
            log.error("submitTask error ", e);
            throw new AurisException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<LongTaskResp> getTask(@PathVariable String taskId) {
        // 异常映射在 Service 层完成,这里不 catch——
        // AurisException 会被 GlobalExceptionHandler 按映射后的状态码渲染
        return ApiResponse.ok(transcribeService.getTask(taskId));
    }

}
