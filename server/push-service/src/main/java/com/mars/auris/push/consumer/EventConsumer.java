// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.push.consumer;

import com.mars.auris.common.utils.JsonUtils;
import com.mars.auris.push.cache.UnreadCounter;
import com.mars.auris.push.event.TranscribeEvent;
import com.mars.auris.push.service.ChannelRouterService;
import com.mars.auris.push.service.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * @author geyan
 * @date 2026/9/13
 */
@Slf4j
@Component
public class EventConsumer {

    @Autowired
    private NoticeService noticeService;
    @Autowired
    private ChannelRouterService routerService;
    @Autowired
    private UnreadCounter unreadCounter;

    @KafkaListener(topics = "auris-event", groupId = "push-service", containerFactory = "manualAckFactory")
    public void onEvent(String message, Acknowledgment ack) {
        TranscribeEvent event = JsonUtils.fromJson(message, TranscribeEvent.class);
        if (event == null) {
            // Parse error — would fail even on 10k retries; time to stop
            log.error("Event unparseable: {}", message);            ack.acknowledge();
            return;
        }

        try {
            NoticeService.CreateResult createResult = noticeService.createIfAbsent(event);
            if (createResult == null) {
                log.error("Persist result abnormal, not acking, awaiting redelivery: eventId={}", event.getEventId());
                return;
            }
            if (createResult.created()) {
                // +1 only for newly created notifications; duplicate events (uk_event hit) don't double-count — counter idempotency follows pool-write idempotency
                unreadCounter.incr(event.getUserId());
            }
            routerService.route(createResult.id(), event);
        } catch (Exception e) {
            log.error("Event processing failed (no ack, awaiting redelivery): {}", message, e);
            return;
        }
        ack.acknowledge();
    }
}
