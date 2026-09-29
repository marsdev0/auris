package com.mars.auris.push.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Data
@TableName("notice")
public class NoticeDO {

    /**
     * Snowflake ID(19 位)。
     * <p>
     * JSON 输出转 String:超出 JS Number 2^53,数字形态会让前端尾数变 0(P3 契约铁律 1)。
     */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 提交用户,数据隔离键,分表键
     */
    private Long userId;

    /**
     * 源事件ID(uuid),消费幂等键(uk_event)
     */
    private String eventId;

    /**
     * 通知类型:transcribe.completed/transcribe.failed/transcribe.timeout/alert.raised...
     */
    private String type;

    /**
     * 通知标题,如「转写完成:《xxx》」,落池时模板化生成
     */
    private String title;

    /**
     * 通知正文(一句话摘要+recordId,点击跳转)
     */
    private String content;

    /**
     * 0-未读 1-已读
     */
    private Integer readFlag;

    /**
     * 创建时间(UTC,列表排序键)
     */
    private LocalDateTime createdAt;

}
