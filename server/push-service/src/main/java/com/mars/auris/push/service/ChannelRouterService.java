package com.mars.auris.push.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mars.auris.push.cache.PreferenceCache;
import com.mars.auris.push.common.Channel;
import com.mars.auris.push.common.Delivery;
import com.mars.auris.push.entity.DeliveryDO;
import com.mars.auris.push.event.TranscribeEvent;
import com.mars.auris.push.mapper.DeliveryMapper;
import com.mars.auris.push.model.DeliveryTask;
import com.mars.auris.push.producer.DeliveryProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Service
public class ChannelRouterService {

    @Autowired
    private PreferenceCache preferenceCache;
    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private DeliveryProducer deliveryProducer;

    /**
     * Channel delivery:
     * 1. write the table
     * 2. the Kafka notification consumer queries the table
     */
    public void route(Long notificationId, TranscribeEvent e) {
        if (e == null) {
            return;
        }
        // 1. Query supported channels
        int channelMask = preferenceCache.maskOf(e.getUserId(), e.getType());
        for (Channel ch : Channel.values()) {
            if ((channelMask & ch.getBit()) == 0) {
                // Channel not supported
                continue;
            }
            DeliveryDO r = new DeliveryDO();
            r.setNotificationId(notificationId);
            r.setChannel(ch.getCode());
            r.setStatus(Delivery.PENDING.getCode());

            try {
                deliveryMapper.insert(r);
            } catch (DuplicateKeyException exception) {
                // uk hit — check if status is 0; if so the message may never have been delivered, needs a make-up send
                // Make-up send is safe: worker-side markSent CAS absorbs it idempotently; at-least-once is fine here
                LambdaQueryWrapper<DeliveryDO> query = new LambdaQueryWrapper<>();
                query.eq(DeliveryDO::getNotificationId, notificationId)
                        .eq(DeliveryDO::getChannel, r.getChannel());
                DeliveryDO exist = deliveryMapper.selectOne(query);
                if (exist != null && exist.getStatus() == Delivery.PENDING.getCode()) {
                    deliveryProducer.sendDelivery(DeliveryTask.builder()
                            .deliveryId(String.valueOf(exist.getId()))
                            .noticeId(String.valueOf(exist.getNotificationId()))
                            .channel(exist.getChannel())
                            .attempt(0).build());
                }
                continue;
            }

            // 2. Kafka notification consumer
            deliveryProducer.sendDelivery(DeliveryTask.builder()
                    .deliveryId(String.valueOf(r.getId()))
                    .noticeId(String.valueOf(r.getNotificationId()))
                    .channel(r.getChannel())
                    .attempt(0).build());
        }
    }
}
