// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @author geyan
 * @date 2026/10/26
 */
@Data
public class DubbingSubmitReq {

    @NotBlank(message = "url must not be blank")
    @Size(max = 1024, message = "url must be at most 1024 characters")
    private String url;
}
