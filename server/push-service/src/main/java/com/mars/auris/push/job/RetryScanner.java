package com.mars.auris.push.job;

import com.mars.auris.push.mapper.DeliveryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 【核心层 · 由你重写】DB 驱动重试:delivery 表自造的延迟队列。
 * <p>
 * 原实现是照方案文档抄的,已清空——这块是深挖点 A 的面试核心,先合上文档自己写,
 * 卡住了再看 vault:03-project/01-auris/03-控制服务/P5-通知服务方案.md §1.5「RetryScanner」与 §A5。
 * <p>
 * 只给需求不给答案:
 * 1. Kafka 没有延迟消息,渠道投递失败后的"过 N 分钟再试"只能自己造——
 *    delivery 表 status=2(retrying) + next_retry_at 就是那条"改日再送"的延迟队列,本 Job 每 30s 翻一次;
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
     * 认领租约:超时未落终态的 sending 行视为认领实例崩溃,拨回 retrying 重来
     */
    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Scheduled(fixedDelay = 30_000)
    public void scan() {
        // TODO@geyan 思考:下面三步的调用顺序有关系吗?为什么回收租约要放在到点重试之前?
        reclaimExpiredLeases();
        retryDue();
        deadLetter();
    }

    /**
     * ③ 租约回收:status=3(sending) 且 next_retry_at 已过期的行,拨回 2(retrying)
     */
    private void reclaimExpiredLeases() {
        // TODO@geyan 核心层:调用 mapper 的哪个原语?"无条件下拨回"为什么是安全的(不会误伤正在发送的行)?
    }

    /**
     * ① 到点重试:认领(2→3)成功才发回原渠道 topic,attempt 原值透传
     */
    private void retryDue() {
        // TODO@geyan 核心层:为什么不能"查了就发"?
        //                  发回哪个 topic?消息 key 用什么(想想分区保序)?attempt 为什么不在这里 +1?
    }

    /**
     * ② 死信:重试耗尽 → 判死(2→4) + 发 auris-notify-dlq 广播
     */
    private void deadLetter() {
        // TODO@geyan 核心层:判死候选和重试候选的查询条件差在哪?判死后为什么不删行?
    }
}
