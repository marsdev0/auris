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
     * recordId (table primary key). Carried as string: snowflake 19 digits exceed JS 2^53; numeric serialization would zero out the tail
     */
    private String recordId;
}
