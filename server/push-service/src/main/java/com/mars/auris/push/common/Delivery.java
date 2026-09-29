package com.mars.auris.push.common;

import lombok.Getter;

/**
 * 渠道投递状态机(delivery.status 列,0-4)
 * <p>
 * 一生的迁移:Router 占位(RUNNING)→ Worker 认领(SENDING)→ 发送成功(SENT)或
 * 失败(RETRYING,按 attempt 指数退避写 next_retry_at)→ RetryScanner 到点重投
 * 回 SENDING;attempt >= 5 判死(FAILED_DQ),冗余一份进死信 topic,等运维 requeue。
 * SENT / FAILED_DQ 是终态,其余状态可循环。
 * code 与 SQL 列注释的 0-4 一一对应,显式声明、不随枚举声明顺序漂移。
 *
 * @author geyan
 * @date 2026/9/16
 */
@Getter
public enum Delivery {

    /**
     * 0-占位:Router 落库即防重(uk_notify_channel 幂等键),此时尚未发送
     */
    RUNNING(0),

    /**
     * 1-已送达:终态,渠道调用成功
     */
    SENT(1),

    /**
     * 2-待重试:上次失败,处于退避等待;next_retry_at 是 RetryScanner 的扫描键
     */
    RETRYING(2),

    /**
     * 3-发送中:已被认领(多实例安全:UPDATE 原子抢占,影响行数=1 才算抢到)
     */
    SENDING(3),

    /**
     * 4-死信:重试耗尽(attempt>=5),进 DLQ,可人工 requeue 复活
     */
    FAILED_DQ(4);

    private final int code;

    Delivery(int code) {
        this.code = code;
    }
}
