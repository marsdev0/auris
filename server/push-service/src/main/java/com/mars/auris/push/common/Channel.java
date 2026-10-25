package com.mars.auris.push.common;

import lombok.Getter;

/**
 * Delivery channels (delivery.channel column & bit definitions of channel_mask & channel topic suffix — three-in-one)
 * <p>
 * bits must be declared explicitly, not derived from ordinal — they are persistence semantics (stored in users' channel_mask);
 * reordering the enum would destroy stored data. New channel = add one enum value (next power of 2).
 *
 * @author geyan
 * @date 2026/9/16
 */
@Getter
public enum Channel {

    /**
     * In-app: local delivery, write-to-DB is delivery
     */
    INAPP(1, "inapp"),

    /**
     * Email: SMTP gateway, slow channel
     */
    EMAIL(2, "email"),

    /**
     * Feishu group bot: webhook + token-bucket rate limit (~100 msg/min hard cap)
     */
    FEISHU(4, "feishu");

    private final int bit;
    private final String code;

    Channel(int bit, String code) {
        this.bit = bit;
        this.code = code;
    }
}
