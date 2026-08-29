// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.model.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author geyan
 * @date 2026/8/29
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EngineAsrSegment {

    private double start;

    private double end;

    private String text;

    private List<EngineAsrWord> words;
}
