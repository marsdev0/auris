package com.mars.auris.ai.transcribe.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mars.auris.ai.config.TranscribeProperties;
import com.mars.auris.ai.model.EngineResp;
import com.mars.auris.ai.transcribe.common.TranscribeConst;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import com.mars.auris.ai.transcribe.mapper.TranscribeRecordMapper;
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
 * RecordService 纯单元测试(mock mapper,不起 Spring 容器):
 * 覆盖 P3 Step 7 的核心语义——预占回填、入口 source、幂等条件、归属删除。
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

    @InjectMocks
    private TranscribeRecordService recordService;

    // ========== createTask:预占 ==========

    @Test
    void createTask_insertsWithSourceAndTitle_returnsSnowflakeId() {
        // 模拟 MP 的 ASSIGN_ID 回填:insert 时把生成 id 写回 entity
        doAnswer(inv -> {
            TranscribeRecordDO item = inv.getArgument(0);
            item.setId(2098825091037216769L);
            return 1;
        }).when(recordMapper).insert(any(TranscribeRecordDO.class));

        Long id = recordService.createTask(1L, "task-abc", TranscribeConst.SOURCE_URL, "标题");

        assertEquals(2098825091037216769L, id);
        ArgumentCaptor<TranscribeRecordDO> captor = ArgumentCaptor.forClass(TranscribeRecordDO.class);
        verify(recordMapper).insert(captor.capture());
        // 预占语义:user_id 隔离键 + engine_task_id + 入口 + transcribing 初始态 + title
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

        // 续用已存在记录的真 recordId,不返回插入未成功的假 id(111)
        assertEquals(222L, recordService.createTask(1L, "task-abc", TranscribeConst.SOURCE_URL, null));
    }

    @Test
    void createTask_duplicateKeyButNotOwned_throwsInternalError() {
        doAnswer(inv -> {
            throw new DuplicateKeyException("uk");
        }).when(recordMapper).insert(any(TranscribeRecordDO.class));
        // 撞了全局唯一键但按归属查不到 = 跨用户撞 uuid,超出模型
        when(recordMapper.selectByUserIdAndEngineTaskId(1L, "task-abc")).thenReturn(null);

        AurisException ex = assertThrows(AurisException.class,
                () -> recordService.createTask(1L, "task-abc", TranscribeConst.SOURCE_URL, null));
        assertEquals(CommonErrorCode.INTERNAL_ERROR.getCode(), ex.getCode());
    }

    // ========== complete:回填(幂等由 SQL 条件保证,mapper 收到正确参数) ==========

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
        // engine 返回 completed 但 result 缺失:不 NPE,text 落 null
        AsrTaskDTO dto = new AsrTaskDTO();
        dto.setResult(null);

        recordService.complete(1L, 2L, dto);

        verify(recordMapper).complete(2L, 1L, null, null);
    }

    // ========== saveTranscribeSyncResult:同步直插终态 + 入库失败不抛 ==========

    @Test
    void saveSync_directInsertAsCompleted_neverThrows() {
        AsrResultDTO data = new AsrResultDTO();
        data.setText("同步结果");
        when(recordMapper.insert(any(TranscribeRecordDO.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(1);

        // 故障语义:engine 已成功,insert 失败只 log 不阻断响应
        recordService.saveTranscribeSyncResult(1L, new EngineResp<>(0, "ok", data));

        ArgumentCaptor<TranscribeRecordDO> captor = ArgumentCaptor.forClass(TranscribeRecordDO.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(TranscribeConst.SOURCE_UPLOAD_SYNC, captor.getValue().getSource());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals("同步结果", captor.getValue().getText());
    }

    // ========== delete:0 行 false 由调用方转 404 ==========

    @Test
    void delete_mapperReceivesIsolationKey() {
        when(recordMapper.deleteByIdAndUserId(9L, 1L)).thenReturn(1);
        assertEquals(true, recordService.deleteByUserIdAndRecordId(1L, 9L));
        when(recordMapper.deleteByIdAndUserId(9L, 1L)).thenReturn(0);
        assertEquals(false, recordService.deleteByUserIdAndRecordId(1L, 9L));
    }

    // ========== failTimeout:定时兜底透传 cutoff 与文案 ==========

    @Test
    void failTimeout_delegatesCutoffAndMsg() {
        when(transcribeProperties.getTimeoutHours()).thenReturn(2);
        when(recordMapper.failTimeout(any(), anyString())).thenReturn(3);
        int n = recordService.failTimeoutRecords();
        assertEquals(3, n);
        verify(recordMapper).failTimeout(any(), eq("转写超时未完成"));
    }
}
