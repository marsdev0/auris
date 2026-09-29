package com.mars.auris.push.common;

import lombok.Getter;

/**
 * 投递渠道(delivery.channel 列 & channel_mask 的位定义 & 渠道 topic 后缀,三位一体)
 * <p>
 * bit 必须显式声明,不能用 ordinal 推导——它是持久化语义(存在用户的 channel_mask 里),
 * 枚举重排会炸掉已存数据。新增渠道 = 加一个枚举值(分配下一个 2 的幂)。
 *
 * @author geyan
 * @date 2026/9/16
 */
@Getter
public enum Channel {

    /**
     * 站内信:本地投递,写库即达
     */
    INAPP(1, "inapp"),

    /**
     * Email:SMTP 网关,慢渠道
     */
    EMAIL(2, "email"),

    /**
     * 飞书群机器人:webhook + 令牌桶限速(~100 条/min 硬限)
     */
    FEISHU(4, "feishu");

    private final int bit;
    private final String code;

    Channel(int bit, String code) {
        this.bit = bit;
        this.code = code;
    }
}
