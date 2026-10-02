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
     * Snowflake ID(即消息里的 delivery_id)
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属通知(notification.id)
     */
    private Long notificationId;

    /**
     * 投递渠道:inapp/email/feishu
     */
    private String channel;

    /**
     * 0-pending占位 1-sent 2-backoff 3-sending认领中 4-failed_dq死信
     */
    private Integer status;

    /**
     * 已尝试次数(退避 1s/2s/4s/8s/16s,>=5 判死)
     */
    private Integer attempt;

    /**
     * 下次重试时间(UTC;backoff 时非空,RetryScanner 扫描键;delivery 表即延迟队列)
     */
    private LocalDateTime nextRetryAt;

    /**
     * 最近一次失败原因(渠道返回,超长截断)
     */
    private String errorMsg;

    /**
     * 状态迁移时间(UTC)
     */
    private LocalDateTime updatedAt;

}
