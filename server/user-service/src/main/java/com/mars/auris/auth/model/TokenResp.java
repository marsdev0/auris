package com.mars.auris.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenResp {

    private String accessToken;

    private String refreshToken;
}
