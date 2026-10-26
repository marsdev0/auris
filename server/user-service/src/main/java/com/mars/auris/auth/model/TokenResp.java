// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
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
