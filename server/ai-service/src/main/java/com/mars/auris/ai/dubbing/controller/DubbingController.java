// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.controller;

import com.mars.auris.ai.dubbing.model.response.AssetUrlResp;
import com.mars.auris.ai.dubbing.model.response.DubbingTaskResp;
import com.mars.auris.ai.dubbing.model.request.DubbingSubmitReq;
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
 * Dubbing task API (D6/D8): submit/list/status/delete + asset streaming proxy + presigned asset-url.
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

    /** Artifact streaming proxy (legacy, kept as fallback): no ApiResponse wrapping, the body is the artifact bytes (kind=audio|video|srt). */
    @GetMapping("/{id}/asset")
    public void asset(@AuthenticationPrincipal UserPrincipal user,
                      @PathVariable Long id,
                      @RequestParam(value = "kind", defaultValue = "audio") String kind,
                      @RequestHeader(value = "Range", required = false) String range,
                      HttpServletResponse resp) {
        dubbingService.streamAsset(user.userId(), id, kind, range, resp);
    }

    /**
     * 产物预签名
     * URL 签发, Java从"流字节"变"发凭证", 浏览器直连对象存储
     */
    @GetMapping("/{id}/asset-url")
    public ApiResponse<AssetUrlResp> assetUrl(@AuthenticationPrincipal UserPrincipal user,
                                              @PathVariable Long id,
                                              @RequestParam(defaultValue = "audio") String kind) {
        return ApiResponse.ok(dubbingService.signAsset(user.userId(), id, kind));
    }
}
