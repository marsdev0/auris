// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.convert;

import com.mars.auris.ai.transcribe.model.LongTaskResp;
import com.mars.auris.ai.transcribe.model.TranscribeResp;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
import com.mars.auris.ai.transcribe.model.engine.AsrTaskDTO;
import org.mapstruct.Mapper;

/**
 * @author geyan
 * @date 2026/8/29
 */
@Mapper(componentModel = "spring")
public interface TranscribeConvert {

    TranscribeResp to(AsrResultDTO dto);

    LongTaskResp to(AsrTaskDTO dto);
}
