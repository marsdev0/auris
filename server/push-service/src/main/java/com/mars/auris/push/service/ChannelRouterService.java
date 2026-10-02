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
     * 渠道的投递
     * 1. 写表
     * 2. kafka通知消费者查表
     */
    public void route(Long notificationId, TranscribeEvent e) {
        if (e == null) {
            return;
        }
        // 1. 查询支持的渠道
        int channelMask = preferenceCache.maskOf(e.getUserId(), e.getType());
        for (Channel ch : Channel.values()) {
            if ((channelMask & ch.getBit()) == 0) {
                // 不支持该渠道
                continue;
            }
            DeliveryDO r = new DeliveryDO();
            r.setNotificationId(notificationId);
            r.setChannel(ch.getCode());
            r.setStatus(Delivery.PENDING.getCode());

            try {
                deliveryMapper.insert(r);
            } catch (DuplicateKeyException exception) {
                // uk命中，检查status是否为0，如果是，消息可能从未送达，此时需要补发
                // 补发安全: worker端 markSent CAS幂等吸收，这里可以使用at-least-once
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

            // 2. kafka通知消费者
            deliveryProducer.sendDelivery(DeliveryTask.builder()
                    .deliveryId(String.valueOf(r.getId()))
                    .noticeId(String.valueOf(r.getNotificationId()))
                    .channel(r.getChannel())
                    .attempt(0).build());
        }
    }
}
