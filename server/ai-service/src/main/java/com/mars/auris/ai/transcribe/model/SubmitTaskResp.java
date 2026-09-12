// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/8/28
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitTaskResp {

    /**
     * recordId(表主键)。字符串承载:雪花 19 位超出 JS 2^53,数字序列化尾数会变 0
     */
    private String recordId;
}
