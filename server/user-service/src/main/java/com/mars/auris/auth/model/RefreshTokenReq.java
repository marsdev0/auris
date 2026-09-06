package com.mars.auris.auth.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Data
public class RefreshTokenReq {

    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
