// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.auth.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Data
public class RefreshTokenReq {

    @NotBlank(message = "refreshToken must not be blank")
    private String refreshToken;
}
