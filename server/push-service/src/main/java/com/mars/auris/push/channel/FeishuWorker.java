package com.mars.auris.push.channel;

import com.mars.auris.push.feishu.FeishuClient;
import com.mars.auris.push.feishu.NoticeCardBuilder;
import com.mars.auris.push.feishu.TokenBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 飞书渠道 worker:消费投递指令 → 限速 → 发交互卡片。
 * 失败(未配置/限流封禁/网络)抛异常由 handle 统一转 markRetry 退避。
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
            // 阻塞限速:洪峰排队在 topic(lag 可观测),attempt 不被限速误伤
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
