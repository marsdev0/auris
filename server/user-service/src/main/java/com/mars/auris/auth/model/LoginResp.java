package com.mars.auris.auth.model;

import lombok.Builder;
import lombok.Data;

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
}
