package com.mars.auris.ai.transcribe.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import com.mars.auris.ai.transcribe.mapper.TranscribeRecordMapper;
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

    public void saveTranscribeSyncResult(Long userId, EngineResp<AsrResultDTO> result) {
        try {
            TranscribeRecordDO item = new TranscribeRecordDO();
            item.setUserId(userId);
            item.setSource(1);
            item.setText(result.getData().getText());
            item.setStatus(1);
            List<AsrSegment> segments = result.getData().getSegments();
            if (segments != null && !segments.isEmpty()) {
                item.setSegmentsJson(JsonUtils.toJson(segments));
                // segment 起止单位为秒(float),duration 取 max(end) 转 ms(P3 §3)
                item.setDurationMs((int) Math.round(segments.stream()
                        .mapToDouble(AsrSegment::getEnd).max().orElse(0) * 1000));
            }
            recordMapper.insert(item);
        } catch (Exception e) {
            log.error("saveTranscribeSyncResult error, userId: {}", userId, e);
        }
    }

    public Long createTask(Long userId, String engineTaskId) {
        TranscribeRecordDO item = new TranscribeRecordDO();
        item.setUserId(userId);
        item.setEngineTaskId(engineTaskId);
        item.setStatus(0);

        try {
            // 不需要先查询再插入，有并发问题，直接插入即可
            recordMapper.insert(item);
        } catch (DuplicateKeyException e) {
            // 不能直接返回 item.getId():插入未成功,那是未落库的假 id;查回已存在记录,续用其 recordId
            log.error("engine_task_id 重复, userId: {}, taskId: {}", userId, engineTaskId);
            TranscribeRecordDO recordDO = recordMapper.selectByUserIdAndEngineTaskId(userId, engineTaskId);
            if (recordDO == null) {
                // 撞了全局唯一键但按归属查不到 = 跨用户撞 uuid,超出模型的异常状态
                throw new AurisException(CommonErrorCode.INTERNAL_ERROR);
            }
            return recordDO.getId();
        }
        return item.getId();
    }

    public void complete(Long userId, Long id, AsrTaskDTO result) {
        recordMapper.complete(id, userId, result.getResult().getText());
    }

    public void fail(Long userId, Long id, String errorMsg) {
        recordMapper.fail(id, userId, errorMsg);
    }

    public TranscribeRecordDO findByUserIdAndRecordId(Long userId, Long id) {
        return recordMapper.selectByIdAndUserId(id, userId);
    }

    public boolean deleteByUserIdAndRecordId(Long userId, Long recordId) {
        return recordMapper.deleteByIdAndUserId(recordId, userId) > 0;
    }

    /**
     * 不查 text
     */
    public Page<TranscribeRecordDO> pageByUserId(Long userId, long page, long size) {
        return recordMapper.pageByUserId(new Page<>(page, size), userId);
    }
}
