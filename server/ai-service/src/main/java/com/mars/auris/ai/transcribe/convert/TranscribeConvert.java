// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.transcribe.convert;

import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import com.mars.auris.ai.transcribe.model.LongTaskResp;
import com.mars.auris.ai.transcribe.model.RecordItemResp;
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

    /**
     * 终态记录回源:DB int 状态 → engine 字符串状态,对齐 AsrTaskDTO 的状态词
     */
    default LongTaskResp to(TranscribeRecordDO record) {
        LongTaskResp resp = new LongTaskResp();
        resp.setRecordId(String.valueOf(record.getId()));
        resp.setTitle(record.getTitle());
        resp.setProgress(1.0);
        if (record.getStatus() == 1) {
            resp.setStatus(TranscribeConst.ENGINE_STATUS_COMPLETED);
            LongTaskResp.Result result = new LongTaskResp.Result();
            result.setText(record.getText());
            resp.setResult(result);
        } else {
            resp.setStatus(TranscribeConst.ENGINE_STATUS_FAILED);
            resp.setErrorMsg(record.getErrorMsg());
        }
        return resp;
    }

    default RecordItemResp toItem(TranscribeRecordDO record) {
        RecordItemResp item = new RecordItemResp();
        item.setRecordId(String.valueOf(record.getId()));
        item.setTitle(record.getTitle());
        // DB int 0/1/2 → 状态词,与轮询响应对齐
        item.setStatus(record.getStatus() == 1 ? TranscribeConst.ENGINE_STATUS_COMPLETED
                : record.getStatus() == 2 ? TranscribeConst.ENGINE_STATUS_FAILED
                : TranscribeConst.ENGINE_STATUS_TRANSCRIBING);
        item.setDurationMs(record.getDurationMs());
        item.setCreatedAt(record.getCreatedAt());
        return item;
    }
}
