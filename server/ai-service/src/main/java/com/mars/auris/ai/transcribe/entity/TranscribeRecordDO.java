package com.mars.auris.ai.transcribe.entity;

import  com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/9/11
 */
@Data
@TableName("transcribe_record")
public class TranscribeRecordDO {

    /**
     * Snowflake ID(对外 recordId)
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 提交用户,数据隔离键
     */
    private Long userId;

    /**
     * engine 任务ID(uuid hex),追溯用
     */
    private String engineTaskId;

    /**
     * 0-异步 or 1-同步
     */
    private Integer source;

    /**
     * 文件名(提交时取 originalFilename 的 basename)
     */
    private String title;

    /**
     * 时长(完成时从 segments max(end_ms) 推导)
     */
    private Integer durationMs;

    /**
     * 转写全文(完成时填)
     */
    private String text;

    /**
     * 段级明细(带时间戳,P6 章节大纲直接可用)
     */
    private String segmentsJson;

    /**
     * 0-transcribing 1-completed 2-failed
     */
    private Integer status;

    /**
     * 失败原因(failed 时填,超长截断)
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

}
