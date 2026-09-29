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
     * 用户(隔离键)
     */
    private Long userId;

    /**
     * 通知类型(与 notice.type 同枚举;广播类可配 *)
     */
    private String type;

    /**
     * 渠道位掩码:bit0=inapp bit1=email bit2=feishu,关=0
     */
    private Integer channelMask;

    /**
     * 0-该类型整体退订(opt-out) 1-开启
     */
    private Integer enabled;

}
