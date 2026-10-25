package com.mars.auris.push.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author geyan
 * @date 2026/9/16
 */
@Data
@TableName("user_notice_preference")
public class UserNoticePreferenceDO {

    /**
     * Snowflake ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * User (isolation key)
     */
    private Long userId;

    /**
     * Notification type (same enum as notice.type; broadcast types allow *)
     */
    private String type;

    /**
     * Channel bitmask: bit0=inapp bit1=email bit2=feishu, 0=off
     */
    private Integer channelMask;

    /**
     * 0-unsubscribed from this type entirely (opt-out) 1-enabled
     */
    private Integer enabled;

}
