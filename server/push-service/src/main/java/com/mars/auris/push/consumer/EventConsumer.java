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
        TranscribeEvent event;
        try {
            event = JsonUtils.fromJson(message, TranscribeEvent.class);
        } catch (Exception e) {
            // 解析错误，重试1万次都失败，此时应该结束
            log.error("事件不可解析: {}", message, e);
            ack.acknowledge();
            return;
        }

        try {
            Long noticeId = noticeService.createIfAbsent(event);
            if (noticeId != null) {
                // 做了幂等，如果未投递过，才投递
                routerService.route(noticeId, event);
                // 新建通知才 +1;重复事件(uk_event 命中)不重复计数——计数幂等跟随落池幂等
                unreadCounter.incr(event.getUserId());
            }
        } catch (Exception e) {
            log.error("事件处理失败(不 ack,等待重投): {}", message, e);
            return;
        }
        ack.acknowledge();
    }
}
