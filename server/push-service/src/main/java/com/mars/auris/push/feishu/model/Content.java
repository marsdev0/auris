// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.push.feishu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Plain-text message content (used when msg_type=text)
 *
 * @author geyan
 * @date 2026/10/2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Content {

    private String text;
}
