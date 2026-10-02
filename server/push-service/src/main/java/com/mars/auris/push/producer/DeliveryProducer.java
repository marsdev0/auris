package com.mars.auris.push.producer;

import com.mars.auris.common.utils.JsonUtils;
import com.mars.auris.push.model.DeliveryTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * @author geyan
 * @date 2026/9/30
 */
@Component
public class DeliveryProducer {
    public static final String TOPIC_DELIVERY_PREFIX = "auris-notify-delivery-";
    public static final String TOPIC_DLQ = "auris-notify-dlq";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    public void sendDelivery(DeliveryTask task) {
        kafka.send(TOPIC_DELIVERY_PREFIX + task.getChannel(), task.getNoticeId(), JsonUtils.toJson(task));
    }

    public void sendDlq(DeliveryTask task) {
        kafka.send(TOPIC_DLQ, task.getNoticeId(), JsonUtils.toJson(task));
    }
}
