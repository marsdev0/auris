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
            // 解析错误，重试1万次都失败，此时应该结束
            log.error("事件不可解析: {}", message);
            ack.acknowledge();
            return;
        }

        try {
            NoticeService.CreateResult createResult = noticeService.createIfAbsent(event);
            if (createResult == null) {
                log.error("落池结果异常,不 ack 等待重投: eventId={}", event.getEventId());
                return;
            }
            if (createResult.created()) {
                // 新建通知才 +1;重复事件(uk_event 命中)不重复计数——计数幂等跟随落池幂等
                unreadCounter.incr(event.getUserId());
            }
            routerService.route(createResult.id(), event);
        } catch (Exception e) {
            log.error("事件处理失败(不 ack,等待重投): {}", message, e);
            return;
        }
        ack.acknowledge();
    }
}
