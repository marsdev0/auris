// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.byok.controller;

import com.mars.auris.ai.byok.enums.ModelDomain;
import com.mars.auris.ai.byok.req.SaveConfigReq;
import com.mars.auris.ai.byok.service.ByokService;
import com.mars.auris.ai.byok.vo.ModelConfigVO;
import com.mars.auris.common.auth.UserPrincipal;
import com.mars.auris.common.rsp.ApiResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author geyan
 * @date 2026/10/10
 */
@Slf4j
@RestController
@RequestMapping("/v1/byok")
public class ByokController {

    @Autowired
    private ByokService byokService;

    @GetMapping("/list")
    public ApiResponse<List<ModelConfigVO>> list(@AuthenticationPrincipal UserPrincipal user) {
        log.info("list user: {}", user);
        return ApiResponse.ok(byokService.list(user.userId()));
    }

    @PostMapping("/save")
    public ApiResponse<Boolean> save(@AuthenticationPrincipal UserPrincipal user,
                                     @Valid @RequestBody SaveConfigReq req) {
        log.info("save user: {}", user);
        return ApiResponse.ok(byokService.save(user.userId(), req));
    }

    @PostMapping("/delete")
    public ApiResponse<Boolean> delete(@AuthenticationPrincipal UserPrincipal user,
                                       @RequestParam String domain) {
        // 前端传的domain，需要校验，可能都不是llm、asr、tts，而是其他字符，此时应该在走到DB之前就拦截
        return ApiResponse.ok(byokService.delete(user.userId(), ModelDomain.of(domain)));
    }

    @PostMapping("/validate")
    public ApiResponse<Boolean> validate(@AuthenticationPrincipal UserPrincipal user,
                                         @RequestParam String domain) {
        return ApiResponse.ok(byokService.validate(user.userId(), ModelDomain.of(domain)));
    }
}
