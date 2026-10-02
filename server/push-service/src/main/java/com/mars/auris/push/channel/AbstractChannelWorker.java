package com.mars.auris.push.channel;

import com.mars.auris.common.utils.JsonUtils;
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
            // JsonUtils 解析失败返回 null 而非抛错;消息坏了重投也不会好,ack 跳过,不阻塞分区
            log.error("投递指令不可解析或缺 deliveryId,跳过: {}", msg);
            ack.acknowledge();
            return;
        }
        DeliveryDO deliveryDO = deliveryMapper.selectById(task.getDeliveryId());
        if (deliveryDO == null) {
            // 行不存在(已清理/消息不合法),同样不可恢复,跳过
            log.error("delivery 不存在,跳过: deliveryId={}", task.getDeliveryId());
            ack.acknowledge();
            return;
        }
        NoticeDO noticeDO = noticeMapper.selectById(deliveryDO.getNotificationId());
        if (noticeDO == null) {
            log.error("notice 不存在,跳过: noticeId={}", deliveryDO.getNotificationId());
            ack.acknowledge();
            return;
        }
        boolean ok = false;
        try {
            ok = sender.apply(deliveryDO, noticeDO);
        } catch (Exception e) {
            // 发送异常与返回 false 同语义:走 backoff+退避,不阻断 ack(失败语义在 DB,不在 Kafka)
            log.error("渠道发送异常 deliveryId={}, channel={}", deliveryDO.getId(), deliveryDO.getChannel(), e);
        }
        if (ok) {
            deliveryMapper.markSent(deliveryDO.getId());
        } else {
            // 指数退避：1/2/4/8/16，重试超过5次由其他处理
            deliveryMapper.markRetry(deliveryDO.getId(), deliveryDO.getAttempt()+1,
                    LocalDateTime.now().plusMinutes(1L << deliveryDO.getAttempt()));
        }
        ack.acknowledge();
    }
}
