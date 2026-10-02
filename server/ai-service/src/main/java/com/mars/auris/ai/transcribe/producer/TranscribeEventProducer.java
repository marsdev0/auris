package com.mars.auris.ai.transcribe.producer;

import com.mars.auris.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * @author geyan
 * @date 2026/9/13
 */
@Slf4j
@Component
public class TranscribeEventProducer {

    private static final String EVENT_AURIS = "auris-event";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public void publishTranscribeTerminal(Long userId, Long recordId, String title, String status) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "event_id", eventId,
                "type", "transcribe."+status,
                "user_id", userId,
                "occurred_at", LocalDateTime.now(ZoneOffset.UTC).toString(),
                "payload", Map.of(
                        "record_id", String.valueOf(recordId),
                        "title", title != null ? title : "",
                        "status", status));
        try {
            kafkaTemplate.send(EVENT_AURIS, eventId, JsonUtils.toJson(body));
        } catch (Exception e) {
            log.error("事件发布失败(通知将缺席): recordId={}, type=transcribe.{}", recordId, status, e);
        }
    }
}
