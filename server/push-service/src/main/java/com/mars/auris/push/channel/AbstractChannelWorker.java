// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.push.channel;

import com.mars.auris.common.utils.JsonUtils;
import com.mars.auris.push.common.Delivery;
import com.mars.auris.push.entity.DeliveryDO;
import com.mars.auris.push.entity.NoticeDO;
import com.mars.auris.push.mapper.DeliveryMapper;
import com.mars.auris.push.mapper.NoticeMapper;
import com.mars.auris.push.model.DeliveryTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;
import java.util.function.BiFunction;

/**
 * @author geyan
 * @date 2026/9/16
 */
@Slf4j
public abstract class AbstractChannelWorker {

    @Autowired
    protected DeliveryMapper deliveryMapper;
    @Autowired
    protected NoticeMapper noticeMapper;

    protected void handle(String msg, Acknowledgment ack,
                          BiFunction<DeliveryDO, NoticeDO, Boolean> sender) {
        DeliveryTask task = JsonUtils.fromJson(msg, DeliveryTask.class);
        if (task == null || task.getDeliveryId() == null) {
            // JsonUtils returns null on parse failure instead of throwing; a bad message won't heal by redelivery — ack and skip, don't block the partition
            log.error("Delivery command unparseable or missing deliveryId, skip: {}", msg);            ack.acknowledge();
            return;
        }
        DeliveryDO deliveryDO = deliveryMapper.selectById(task.getDeliveryId());
        if (deliveryDO == null) {
            // Row doesn't exist (cleaned up / invalid message), equally unrecoverable, skip
            log.error("delivery not found, skip: deliveryId={}", task.getDeliveryId());            ack.acknowledge();
            return;
        }
        if (deliveryDO.getStatus() != Delivery.PENDING.getCode()
                && deliveryDO.getStatus() != Delivery.SENDING.getCode()) {
            // Only these two statuses may send to a channel
            log.info("Row no longer in-flight, skip: deliveryId={}, status={}",                    deliveryDO.getId(), deliveryDO.getStatus());
            ack.acknowledge();
            return;
        }
        NoticeDO noticeDO = noticeMapper.selectById(deliveryDO.getNotificationId());
        if (noticeDO == null) {
            log.error("notice not found, skip: noticeId={}", deliveryDO.getNotificationId());
            ack.acknowledge();
            return;
        }
        boolean ok = false;
        try {
            ok = sender.apply(deliveryDO, noticeDO);
        } catch (Exception e) {
            // Send exceptions and false returns share semantics: backoff with delay, don't block ack (failure semantics live in the DB, not Kafka)
            log.error("Channel send failed, deliveryId={}, channel={}", deliveryDO.getId(), deliveryDO.getChannel(), e);        }
        if (ok) {
            deliveryMapper.markSent(deliveryDO.getId());
        } else {
            // Exponential backoff: 1/2/4/8/16; over 5 retries handled elsewhere
            deliveryMapper.markRetry(deliveryDO.getId(), deliveryDO.getAttempt()+1,
                    LocalDateTime.now().plusMinutes(1L << deliveryDO.getAttempt()));
        }
        ack.acknowledge();
    }
}
