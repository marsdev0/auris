package com.mars.auris.auth.controller;

import com.mars.auris.auth.filter.CustomUserDetails;
import com.mars.auris.auth.model.LoginReq;
import com.mars.auris.auth.model.LoginResp;
import com.mars.auris.auth.model.RefreshTokenReq;
import com.mars.auris.auth.model.TokenResp;
import com.mars.auris.common.rsp.ApiResponse;
import com.mars.auris.auth.model.RegisterReq;
import com.mars.auris.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author geyan
 * @date 2026/9/4
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterReq req) {
        authService.register(req);
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return ApiResponse.ok(authService.login(req));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.logout(userDetails.getUserId());
        return ApiResponse.ok();
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResp> refresh(@Valid @RequestBody RefreshTokenReq req) {
        return ApiResponse.ok(authService.refresh(req));
    }
}
