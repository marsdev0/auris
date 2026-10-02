package com.mars.auris.push.job;

import com.mars.auris.push.entity.DeliveryDO;
import com.mars.auris.push.mapper.DeliveryMapper;
import com.mars.auris.push.model.DeliveryTask;
import com.mars.auris.push.producer.DeliveryProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 【核心层 · 由你重写】DB 驱动重试:delivery 表自造的延迟队列。
 * <p>
 * 原实现是照方案文档抄的,已清空——这块是深挖点 A 的面试核心,先合上文档自己写,
 * 卡住了再看 vault:03-project/01-auris/03-控制服务/P5-通知服务方案.md §1.5「RetryScanner」与 §A5。
 * <p>
 * 只给需求不给答案:
 * 1. Kafka 没有延迟消息,渠道投递失败后的"过 N 分钟再试"只能自己造——
 * delivery 表 status=2(backoff) + next_retry_at 就是那条"改日再送"的延迟队列,本 Job 每 30s 翻一次;
 * 2. 多实例安全:两个 push-service 实例同轮都会扫到同一行,不能各发一次——用认领(CAS)语义;
 * 3. 认领后实例崩溃的行要能自愈(提示:认领时把 next_retry_at 推到了未来,它怎么兼作租约?);
 * 4. attempt >= MAX_ATTEMPT 判死:置 FAILED_DQ + 广播 auris-notify-dlq(行不删,留给运维 requeue 救回)。
 * <p>
 * 原料:DeliveryMapper 的 selectDueRetries / claimSending / claimDead / reclaimExpiredLeases
 * 四个原语已备好——动笔前逐条读它们的 WHERE 条件,认领语义全在里面。
 * 口述验收:写完后不看代码讲 5 分钟(为什么要 DB 扫表不走 Kafka 重投?CAS 防什么?租约防什么?)
 *
 * @author geyan
 * @date 2026/9/16
 */
@Slf4j
@Component
public class RetryScanner {

    /**
     * attempt>=5 判死(退避 1m/2m/4m/8m,共 5 次)
     */
    private static final int MAX_ATTEMPT = 5;
    /**
     * 单轮扫描批量上限,防一轮捞太多把渠道打挂
     */
    private static final int BATCH_LIMIT = 100;
    /**
     * 认领租约:超时未落终态的 sending 行视为认领实例崩溃,拨回 backoff 重来
     */
    private static final Duration LEASE = Duration.ofSeconds(60);

    /**
     * 首发孤儿判定窗口:insert 后超过此时长仍是 pending(0),视为消息从未送达。
     * >> 正常首发秒级耗时,与租约 60s 同量级
     */
    private static final Duration UNSENT_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private DeliveryProducer deliveryProducer;

    @Scheduled(fixedDelay = 30_000)
    public void scan() {
        // 思考:下面三步的调用顺序有关系吗?为什么回收租约要放在到点重试之前?
        reclaimExpiredLeases();  // 3-2：租约过期，认领方已死
        reclaimUnsent();  // 0-2：首发孤儿，消息从未送达
        retryDue();  // 2-3：到点认领重发
        deadLetter();  // 2-4：耗尽判死
    }

    /**
     * ④ 首发孤儿回收:pending(0) 且 updated_at 超过判定窗口的行拨回 2(backoff)。
     * 覆盖"insert 成功但投递消息从未送达"的一切断裂(route 半途异常/broker 长挂/进程崩溃)——
     * 正常首发秒级闭合,超时仍在 0 = 消息大概率没到,拨 2 后走正常认领重发。
     * 与 ③ 同款:逻辑在 mapper 的 WHERE 里,Java 侧只负责触发+计数。
     */
    private void reclaimUnsent() {
        LocalDateTime now = LocalDateTime.now();
        int reclaimed = deliveryMapper.reclaimUnsent(now, now.minus(UNSENT_TIMEOUT));
        if (reclaimed > 0) {
            // 正常长期为 0;突增=首发链路批量断裂(broker 长挂/实例崩溃),自愈机制在工作的信号
            log.warn("reclaimed {} unsent deliveries", reclaimed);
        }
    }

    /**
     * ③ 租约回收:status=3(sending) 且 next_retry_at 已过期的行,拨回 2(backoff)。
     * 逻辑全在 mapper 的 WHERE 里——认领时租约(now+60s)被写进 next_retry_at,
     * 过期即"认领方 60s 没下文,当作已死";Java 侧只负责触发+计数,
     * 时间用 Java 的 now(JVM UTC,与 claimSending 写入同源,不用 DB NOW() 防时区错位)。
     */
    private void reclaimExpiredLeases() {
        int reclaimed = deliveryMapper.reclaimExpiredLeases(LocalDateTime.now());
        if (reclaimed > 0) {
            // 正常长期为 0;突然变大=有实例认领后崩溃/卡死,自愈机制在工作的信号
            log.warn("reclaimed {} expired-lease deliveries", reclaimed);
        }
    }

    /**
     * ① 到点重试:认领(2→3)成功才发回原渠道 topic,attempt 原值透传
     */
    private void retryDue() {
        // 核心层:为什么不能"查了就发"?
        //                  发回哪个 topic?消息 key 用什么(想想分区保序)?attempt 为什么不在这里 +1?

        List<DeliveryDO> deliveryDOS = deliveryMapper.selectDueRetries(LocalDateTime.now(), MAX_ATTEMPT, BATCH_LIMIT);
        if (CollectionUtils.isEmpty(deliveryDOS)) {
            return;
        }
        for (DeliveryDO item : deliveryDOS) {
            // 认领:CAS 2→3,影响行数=1 才算本实例抢到(SELECT 不锁行,两实例同轮会扫到同一批)
            int result = deliveryMapper.claimSending(item.getId(), LocalDateTime.now().plus(LEASE));
            if (result > 0) {
                try {
                    deliveryProducer.sendDelivery(DeliveryTask.builder()
                            .deliveryId(String.valueOf(item.getId()))
                            .noticeId(String.valueOf(item.getNotificationId()))
                            .channel(item.getChannel())
                            .attempt(item.getAttempt()).build());
                } catch (Exception e) {
                    // 不回滚不补偿:行=3 且租约 60s,到期 reclaimExpiredLeases 收尸重排。
                    // catch 只为隔离——别让一条的 send 异常打断整批(剩余行还在 2,下轮照捞,只是慢一轮)
                    log.error("重投发送失败,等待租约回收自愈: deliveryId={}", item.getId(), e);
                }
            }
        }
    }

    /**
     * ② 死信:重试耗尽 → 判死(2→4) + 发 auris-notify-dlq 广播
     */
    private void deadLetter() {
        // 核心层:判死候选和重试候选的查询条件差在哪?判死后为什么不删行?
        List<DeliveryDO> deliveryDOS = deliveryMapper.selectExhausted(MAX_ATTEMPT, BATCH_LIMIT);
        if (CollectionUtils.isEmpty(deliveryDOS)) {
            return;
        }
        for (DeliveryDO item : deliveryDOS) {
            // 如同上面一样，始终要考虑多实例，因此，这个只有一个实例修改成功后，才发topic
            int result = deliveryMapper.claimDead(item.getId());
            if (result > 0) {
                deliveryProducer.sendDlq(DeliveryTask.builder()
                        .deliveryId(String.valueOf(item.getId()))
                        .noticeId(String.valueOf(item.getNotificationId()))
                        .channel(item.getChannel())
                        .attempt(item.getAttempt()).build());
            }
        }
    }
}
