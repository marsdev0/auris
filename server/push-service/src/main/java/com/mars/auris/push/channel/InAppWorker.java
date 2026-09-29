package com.mars.auris.push.channel;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * @author geyan
 * @date 2026/9/16
 */
@Component
public class InAppWorker extends AbstractChannelWorker {

    @KafkaListener(topics = "auris-notify-delivery-inapp", groupId = "push-inapp",
                   containerFactory = "manualAckFactory")
    public void onDelivery(String msg, Acknowledgment ack) {
        handle(msg, ack, (d, n) -> true);
    }
}
