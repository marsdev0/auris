package com.mars.auris.ai.transcribe.schedule;

import com.mars.auris.ai.transcribe.service.TranscribeRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @author geyan
 * @date 2026/9/12
 */
@Component
public class CheckTranscribeStatusJob {

    @Autowired
    private TranscribeRecordService recordService;

    /**
     * 定时查 transcribe_record，超2h 状态仍然是转写中，修改状态
     */
    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    public void checkTranscribeStatus() {
        // 异步处理吧？
       recordService.failTimeoutRecords();
    }
}
