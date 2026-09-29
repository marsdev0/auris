package com.mars.auris.push.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TranscribeEvent {

    /**
     * 线上格式是 snake_case(P5 §1.5 消息契约),与 DeliveryTask 同款显式映射
     */
    @JsonProperty("event_id")
    private String eventId;

    private String type;

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("occurred_at")
    private LocalDateTime occurredAt;

    private Map<String, String> payload;
}
