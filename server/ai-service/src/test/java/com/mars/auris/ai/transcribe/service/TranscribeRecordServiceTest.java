package com.mars.auris.ai.transcribe.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mars.auris.ai.config.TranscribeProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import com.mars.auris.ai.transcribe.mapper.TranscribeRecordMapper;
import com.mars.auris.ai.transcribe.producer.TranscribeEventProducer;
import com.mars.auris.ai.transcribe.model.engine.AsrResultDTO;
import com.mars.auris.ai.transcribe.model.engine.AsrTaskDTO;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RecordService pure unit test (mock mapper, no Spring container):
 * covers the core semantics of P3 Step 7 — pre-claim backfill, entry source, idempotency condition, ownership-scoped delete.
 *
 * @author geyan
 * @date 2026/9/13
 */
@ExtendWith(MockitoExtension.class)
class TranscribeRecordServiceTest {

    @Mock
    private TranscribeRecordMapper recordMapper;

    @Mock
    private TranscribeProperties transcribeProperties;

    @Mock
    private TranscribeEventProducer eventProducer;

    @InjectMocks
    private TranscribeRecordService recordService;

    // ========== createTask: pre-claim ==========

    @Test
    void createTask_insertsWithSourceAndTitle_returnsSnowflakeId() {
        // Simulate MP's ASSIGN_ID backfill: insert writes the generated id back into the entity
        doAnswer(inv -> {
            TranscribeRecordDO item = inv.getArgument(0);
            item.setId(2098825091037216769L);
            return 1;
        }).when(recordMapper).insert(any(TranscribeRecordDO.class));

        Long id = recordService.createTask(1L, "task-abc", TranscribeConst.SOURCE_URL, "标题");

        assertEquals(2098825091037216769L, id);
        ArgumentCaptor<TranscribeRecordDO> captor = ArgumentCaptor.forClass(TranscribeRecordDO.class);
        verify(recordMapper).insert(captor.capture());
        // Pre-claim semantics: user_id isolation key + engine_task_id + entry + transcribing initial state + title
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals("task-abc", captor.getValue().getEngineTaskId());
        assertEquals(TranscribeConst.SOURCE_URL, captor.getValue().getSource());
        assertEquals("标题", captor.getValue().getTitle());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void createTask_duplicateKey_fallsBackToExistingRecord() {
        doAnswer(inv -> {
            ((TranscribeRecordDO) inv.getArgument(0)).setId(111L);
            throw new DuplicateKeyException("uk_engine_task_id");
        }).when(recordMapper).insert(any(TranscribeRecordDO.class));
        TranscribeRecordDO existing = new TranscribeRecordDO();
        existing.setId(222L);
        when(recordMapper.selectByUserIdAndEngineTaskId(1L, "task-abc")).thenReturn(existing);

        // Reuses the existing record's real recordId; doesn't return the failed insert's fake id (111)
        assertEquals(222L, recordService.createTask(1L, "task-abc", TranscribeConst.SOURCE_URL, null));
    }

    @Test
    void createTask_duplicateKeyButNotOwned_throwsInternalError() {
        doAnswer(inv -> {
            throw new DuplicateKeyException("uk");
        }).when(recordMapper).insert(any(TranscribeRecordDO.class));
        // Hit the global unique key but not found by ownership = cross-user uuid collision, beyond the model
        when(recordMapper.selectByUserIdAndEngineTaskId(1L, "task-abc")).thenReturn(null);

        AurisException ex = assertThrows(AurisException.class,
                () -> recordService.createTask(1L, "task-abc", TranscribeConst.SOURCE_URL, null));
        assertEquals(CommonErrorCode.INTERNAL_ERROR.getCode(), ex.getCode());
    }

    // ========== complete: backfill (idempotency guaranteed by SQL condition; mapper receives correct params) ==========

    @Test
    void complete_passesTextAndTitleToConditionalUpdate() {
        AsrTaskDTO dto = new AsrTaskDTO();
        AsrTaskDTO.Result r = new AsrTaskDTO.Result();
        r.setText("全文");
        dto.setResult(r);
        dto.setTitle("单集标题");

        recordService.complete(1L, 2L, dto);

        verify(recordMapper).complete(2L, 1L, "全文", "单集标题");
    }

    @Test
    void complete_resultMissing_textFallsBackNull_noNpe() {
        // Engine returns completed but result is missing: no NPE, text falls to null
        AsrTaskDTO dto = new AsrTaskDTO();
        dto.setResult(null);

        recordService.complete(1L, 2L, dto);

        verify(recordMapper).complete(2L, 1L, null, null);
    }

    // ========== saveTranscribeSyncResult: sync direct-insert terminal state + insert failure doesn't throw ==========

    @Test
    void saveSync_directInsertAsCompleted_neverThrows() {
        AsrResultDTO data = new AsrResultDTO();
        data.setText("同步结果");
        when(recordMapper.insert(any(TranscribeRecordDO.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(1);

        // Failure semantics: engine already succeeded; insert failure only logs, doesn't block the response
        recordService.saveTranscribeSyncResult(1L, new EngineResp<>(0, "ok", data));

        ArgumentCaptor<TranscribeRecordDO> captor = ArgumentCaptor.forClass(TranscribeRecordDO.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(TranscribeConst.SOURCE_UPLOAD_SYNC, captor.getValue().getSource());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals("同步结果", captor.getValue().getText());
    }

    // ========== delete: 0 rows → false, caller turns into 404 ==========

    @Test
    void delete_mapperReceivesIsolationKey() {
        when(recordMapper.deleteByIdAndUserId(9L, 1L)).thenReturn(1);
        assertEquals(true, recordService.deleteByUserIdAndRecordId(1L, 9L));
        when(recordMapper.deleteByIdAndUserId(9L, 1L)).thenReturn(0);
        assertEquals(false, recordService.deleteByUserIdAndRecordId(1L, 9L));
    }

    // ========== failTimeout: row-by-row fail + event emit (new semantics after the Step2 rework) ==========

    @Test
    void failTimeout_marksEachAndPublishesEvent() {
        when(transcribeProperties.getTimeoutHours()).thenReturn(2);
        TranscribeRecordDO r = new TranscribeRecordDO();
        r.setId(9L); r.setUserId(7L); r.setTitle("超时单集");
        when(recordMapper.selectTimeoutRecords(any())).thenReturn(List.of(r));

        int n = recordService.failTimeoutRecords();

        assertEquals(1, n);
        // Row-by-row fail(): the status=0 condition is mutually exclusive with polling backfill
        verify(recordMapper).fail(9L, 7L, "转写超时未完成", "超时单集");
        // Each row emits auris-event (timeout is also a terminal state; the notification must reach)
        verify(eventProducer).publishTranscribeTerminal(7L, 9L, "超时单集", "timeout");
    }
}
