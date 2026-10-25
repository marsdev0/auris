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
     * Periodically check transcribe_record: still transcribing after 2h → update status
     */
    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    public void checkTranscribeStatus() {
        // Async processing?
       recordService.failTimeoutRecords();
    }
}
