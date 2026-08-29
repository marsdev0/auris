// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.convert;

import com.mars.auris.ai.model.TranscribeResult;
import com.mars.auris.ai.model.engine.EngineAsrResult;
import org.mapstruct.Mapper;

/**
 * @author geyan
 * @date 2026/8/29
 */
@Mapper(componentModel = "spring")
public interface TranscribeConvert {

    TranscribeResult toResult(EngineAsrResult result);
}
