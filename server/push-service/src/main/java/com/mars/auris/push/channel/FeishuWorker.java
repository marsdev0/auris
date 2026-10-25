package com.mars.auris.push.channel;

import com.mars.auris.push.feishu.FeishuClient;
import com.mars.auris.push.feishu.NoticeCardBuilder;
import com.mars.auris.push.feishu.TokenBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Feishu channel worker: consume delivery instructions → rate-limit → send interactive cards.
 * Failures (unconfigured / rate-limit ban / network) throw; handle uniformly converts to markRetry backoff.
 *
 * @author geyan
 * @date 2026/10/2
 */
@Component
public class FeishuWorker extends AbstractChannelWorker {

    @Autowired
    private FeishuClient feishuClient;
    @Autowired
    private TokenBucket feishuRateLimiter;

    @KafkaListener(topics = "auris-notify-delivery-feishu", groupId = "push-feishu",
                   containerFactory = "manualAckFactory")
    public void onDelivery(String msg, Acknowledgment ack) {
        handle(msg, ack, (delivery, notice) -> {
            // Blocking rate-limit: flood peaks queue in the topic (lag observable); attempt is not punished by rate limiting
            try {
                feishuRateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // 恢复中断位交回框架;false→退避,下次消费重试
                return false;
            }
            return feishuClient.sendCard(NoticeCardBuilder.build(notice));
        });
    }
}
