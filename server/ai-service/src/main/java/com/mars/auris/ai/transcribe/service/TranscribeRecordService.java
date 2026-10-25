package com.mars.auris.ai.transcribe.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mars.auris.ai.config.TranscribeProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.mapper.TranscribeRecordMapper;
import com.mars.auris.ai.transcribe.producer.TranscribeEventProducer;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
import com.mars.auris.ai.transcribe.model.engine.AsrSegment;
import com.mars.auris.ai.transcribe.model.engine.AsrTaskDTO;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import com.mars.auris.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author geyan
 * @date 2026/9/11
 */
@Slf4j
@Service
public class TranscribeRecordService {

    @Autowired
    private TranscribeRecordMapper recordMapper;

    @Autowired
    private TranscribeEventProducer eventProducer;

    @Autowired
    private TranscribeProperties transcribeProperties;

    public void saveTranscribeSyncResult(Long userId, EngineResp<AsrResultDTO> result) {
        try {
            TranscribeRecordDO item = new TranscribeRecordDO();
            item.setUserId(userId);
            item.setSource(TranscribeConst.SOURCE_UPLOAD_SYNC);
            item.setText(result.getData().getText());
            item.setStatus(1);
            List<AsrSegment> segments = result.getData().getSegments();
            if (segments != null && !segments.isEmpty()) {
                item.setSegmentsJson(JsonUtils.toJson(segments));
                // Segment start/end are in seconds (float); duration takes max(end) converted to ms (P3 §3)
                item.setDurationMs((int) Math.round(segments.stream()
                        .mapToDouble(AsrSegment::getEnd).max().orElse(0) * 1000));
            }
            recordMapper.insert(item);
        } catch (Exception e) {
            log.error("saveTranscribeSyncResult error, userId: {}", userId, e);
        }
    }

    public Long createTask(Long userId, String engineTaskId, int source, String title) {
        TranscribeRecordDO item = new TranscribeRecordDO();
        item.setUserId(userId);
        item.setEngineTaskId(engineTaskId);
        item.setSource(source);
        item.setTitle(title);
        item.setStatus(0);

        try {
            // No need to query-then-insert — that has a concurrency problem; just insert directly
            recordMapper.insert(item);
        } catch (DuplicateKeyException e) {
            // Cannot return item.getId(): the insert didn't succeed, that's an unpersisted fake id; query back the existing record, reuse its recordId
            log.error("engine_task_id 重复, userId: {}, taskId: {}", userId, engineTaskId);
            TranscribeRecordDO recordDO = recordMapper.selectByUserIdAndEngineTaskId(userId, engineTaskId);
            if (recordDO == null) {
                // Hit the global unique key but not found by ownership = cross-user uuid collision, beyond the model
                throw new AurisException(CommonErrorCode.INTERNAL_ERROR);
            }
            return recordDO.getId();
        }
        return item.getId();
    }

    public void complete(Long userId, Long id, AsrTaskDTO result) {
        // Defensive: engine returns completed but result is missing — no NPE, text falls to null
        String text = result.getResult() != null ? result.getResult().getText() : null;
        recordMapper.complete(id, userId, text, result.getTitle());
    }

    public void fail(Long userId, Long id, String errorMsg, String title) {
        recordMapper.fail(id, userId, errorMsg, title);
    }

    public TranscribeRecordDO findByUserIdAndRecordId(Long userId, Long id) {
        return recordMapper.selectByIdAndUserId(id, userId);
    }

    public boolean deleteByUserIdAndRecordId(Long userId, Long recordId) {
        return recordMapper.deleteByIdAndUserId(recordId, userId) > 0;
    }

    /**
     * Does not query text
     */
    public Page<TranscribeRecordDO> pageByUserId(Long userId, long page, long size) {
        return recordMapper.pageByUserId(new Page<>(page, size), userId);
    }

    public int failTimeoutRecords() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(transcribeProperties.getTimeoutHours());
        List<TranscribeRecordDO> records = recordMapper.selectTimeoutRecords(cutoff);
        for (TranscribeRecordDO r : records) {
            // Row-by-row fail(): with the status=0 condition, naturally mutually exclusive with polling backfill (P3 §2.4)
            fail(r.getUserId(), r.getId(), "转写超时未完成", r.getTitle());
            eventProducer.publishTranscribeTerminal(r.getUserId(), r.getId(), r.getTitle(), "timeout");
        }
        if (!records.isEmpty()) {
            log.warn("定时兜底:超 {}h 未完成,标 failed {} 条", transcribeProperties.getTimeoutHours(), records.size());
        }
        return records.size();
    }
}
