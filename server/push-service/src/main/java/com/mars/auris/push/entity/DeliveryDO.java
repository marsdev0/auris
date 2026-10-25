package com.mars.auris.push.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Data
@TableName("delivery")
public class DeliveryDO {

    /**
     * Snowflake ID (the delivery_id inside messages)
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Owning notification (notification.id)
     */
    private Long notificationId;

    /**
     * Delivery channel: inapp/email/feishu
     */
    private String channel;

    /**
     * 0-pending placeholder 1-sent 2-backoff 3-sending claimed 4-failed_dq dead letter
     */
    private Integer status;

    /**
     * Attempts so far (backoff 1s/2s/4s/8s/16s; >=5 pronounced dead)
     */
    private Integer attempt;

    /**
     * Next retry time (UTC; non-null in backoff, RetryScanner's scan key; the delivery table is the delay queue)
     */
    private LocalDateTime nextRetryAt;

    /**
     * Last failure reason (returned by the channel, truncated if long)
     */
    private String errorMsg;

    /**
     * Status transition time (UTC)
     */
    private LocalDateTime updatedAt;

}
