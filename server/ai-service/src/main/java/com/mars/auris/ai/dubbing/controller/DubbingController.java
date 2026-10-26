// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.controller;

import com.mars.auris.ai.dubbing.model.DubbingTaskResp;
import com.mars.auris.ai.dubbing.req.DubbingSubmitReq;
import com.mars.auris.ai.dubbing.service.DubbingService;
import com.mars.auris.ai.transcribe.model.PageResp;
import com.mars.auris.ai.transcribe.model.SubmitTaskResp;
import com.mars.auris.common.auth.UserPrincipal;
import com.mars.auris.common.rsp.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 配音任务
 */
@RestController
@RequestMapping("/v1/dubbing")
public class DubbingController {

    @Autowired
    private DubbingService dubbingService;

    @PostMapping
    public ApiResponse<SubmitTaskResp> submit(@AuthenticationPrincipal UserPrincipal user,
                                              @Valid @RequestBody DubbingSubmitReq req) {
        return ApiResponse.ok(dubbingService.submitUrl(user.userId(), req.getUrl()));
    }

    @GetMapping
    public ApiResponse<PageResp<DubbingTaskResp>> list(@AuthenticationPrincipal UserPrincipal user,
                                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                                       @RequestParam(value = "size", defaultValue = "10") int size) {
        return ApiResponse.ok(dubbingService.list(user.userId(), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<DubbingTaskResp> get(@AuthenticationPrincipal UserPrincipal user,
                                            @PathVariable Long id) {
        return ApiResponse.ok(dubbingService.getTask(user.userId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal user,
                                    @PathVariable Long id) {
        dubbingService.delete(user.userId(), id);
        return ApiResponse.ok();
    }

    /**
     * 产物流式代理:不走 ApiResponse 包装,响应体就是产物字节(kind=audio|video|srt)。
     */
    @GetMapping("/{id}/asset")
    public void asset(@AuthenticationPrincipal UserPrincipal user,
                      @PathVariable Long id,
                      @RequestParam(value = "kind", defaultValue = "audio") String kind,
                      @RequestHeader(value = "Range", required = false) String range,
                      HttpServletResponse resp) {
        dubbingService.streamAsset(user.userId(), id, kind, range, resp);
    }
}
