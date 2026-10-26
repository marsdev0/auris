// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.auth.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Data
@Builder
public class LoginResp {

    private String accessToken;

    private String refreshToken;

    private String username;

    private String nickname;

    private String avatarUrl;

    private LocalDateTime createdAt;
}
