package com.mars.auris.push.common;

import lombok.Getter;

/**
 * 渠道投递状态机(delivery.status 列,0-4)。
 * <p>
 * 状态标记的不是"消息在 Kafka 管道里的位置"(DB 看不见管道内部——消息发没发、
 * 消没消费,表无从知晓),而是这一行此刻的账面事实:<b>归谁管、在等什么</b>。
 * <p>
 * 两条链路:首次(Router 落库占位 PENDING → 发渠道 topic → Worker 直接闭合 SENT/BACKOFF);
 * 重试(RetryScanner 到点认领 BACKOFF→SENDING → 重投 → Worker 闭合,失败再退避)。
 * <p>
 * 每个非终态都有巡检员兜底,不存在"无人看守"的状态:
 * <ul>
 *   <li>PENDING(0)  → reclaimUnsent:超 2 分钟仍在 0 = 首发消息从未送达,拨 2 重排</li>
 *   <li>BACKOFF(2)  → selectDueRetries:退避到点,认领重发</li>
 *   <li>SENDING(3)  → reclaimExpiredLeases:租约 60s 到期未回写 = 认领方已死,收尸拨 2</li>
 *   <li>FAILED_DQ(4)→ 人工 requeue(4→2,attempt 清零)</li>
 * </ul>
 * <p>
 * 白话对照:刚下单 / 约了改日再送 / 承运方已接单 / 签收 / 丢件立案。
 * <p>
 * code 是持久化语义:存量行、mapper SQL 字面量、DDL 注释全绑死数字,枚举重排即数据迁移
 * (同 {@link Channel} 的 bit 教训)——名字是给人看的可随时改,code 是给机器存的不能动。
 *
 * @author geyan
 * @date 2026/9/16
 */
@Getter
public enum Delivery {

    /**
     * 0-占位,无主,等首次投递。
     * <p>
     * 入:Router insert 时写入——目的是先占住 uk_notify_channel 幂等键,此刻 Kafka 消息还没发。
     * 出:Worker 首次消费后 markSent/markRetry 直接闭合(首次链路无需 DB 认领,消费组天然独占);
     * 若消息从未送达(route 半途断裂/broker 长挂/进程崩溃),2 分钟后 reclaimUnsent 拨到 2 重排。
     */
    PENDING(0),

    /**
     * 1-终态,渠道送达。Worker 渠道调用成功后 markSent(0/3→1),不可逆。
     */
    SENT(1),

    /**
     * 2-退避等待,无主,等闹钟。
     * <p>
     * 入:Worker 失败 markRetry 时写入(attempt+1,next_retry_at=退避后的时刻)。
     * 此刻什么都没在发生——行在表里等时间到,它不是"正在重试",是"已排定重试"。
     * next_retry_at 是闹钟,也是 selectDueRetries 的扫描键。
     * 出:到点 RetryScanner 认领(2→3)重发;attempt>=5 由 selectExhausted 判死(2→4)。
     * 原名 RETRYING,"等待"误用进行体与 SENDING 撞语义,更名(2026-10-02)。
     */
    BACKOFF(2),

    /**
     * 3-在途,有主(认领实例持有租约),等结果。
     * <p>
     * 入:RetryScanner claimSending 时 CAS 写入(2→3,租约 now+60s 顺手写进 next_retry_at——
     * 这一列在 2 态是退避闹钟,在 3 态被改写为租约死线)。
     * 覆盖窗口:认领起到 Worker 回写止,横跨"发 Kafka 前→消息在 topic 排队→Worker 正在调渠道"
     * 全程——本质是所有权标记(分布式锁):多实例靠 CAS 抢,影响行数=1 者才许投递。
     * 出:Worker markSent/markRetry 回写;租约到期未回写由 reclaimExpiredLeases 收尸(3→2)。
     */
    SENDING(3),

    /**
     * 4-终态,死信。重试耗尽(attempt>=5)由 claimDead 判死(2→4)+广播 auris-notify-dlq 供告警。
     * 行不删,留运维 POST /v1/notification/dlq/{id}/requeue 复活(4→2,attempt 清零,重新走 Scanner)。
     */
    FAILED_DQ(4);

    private final int code;

    Delivery(int code) {
        this.code = code;
    }
}
