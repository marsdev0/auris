package com.mars.auris.push.service;

import com.mars.auris.common.utils.JsonUtils;
import com.mars.auris.push.cache.PreferenceCache;
import com.mars.auris.push.common.Channel;
import com.mars.auris.push.common.Delivery;
import com.mars.auris.push.entity.DeliveryDO;
import com.mars.auris.push.event.TranscribeEvent;
import com.mars.auris.push.mapper.DeliveryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

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
    private KafkaTemplate<String, String> kafka;



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
            r.setStatus(Delivery.RUNNING.getCode());

            try {
                deliveryMapper.insert(r);
            } catch (DuplicateKeyException exception) {
                continue;
            }

            // 2. kafka通知消费者
            kafka.send("auris-notify-delivery-" + ch.getCode(), String.valueOf(notificationId),
                    JsonUtils.toJson(Map.of(
                            "delivery_id", r.getId(),
                            "notice_id", notificationId,
                            "channel", ch.getCode(),
                            "attempt", 0)));
        }
    }
}
