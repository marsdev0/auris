// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.ai.dubbing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 配音任务(D6):audio/video 双形态,MySQL 为任务状态权威源(engine 是执行细节)。 */
@Data
@TableName("dubbing_task")
public class DubbingTaskDO {

    /** 雪花 ID(对外 taskId) */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 提交用户,数据隔离键 */
    private Long userId;

    /** engine 任务ID(uuid hex),追溯用 */
    private String engineTaskId;

    /** 来源 URL */
    private String url;

    /** url 的 md5 hex,幂等判重键(uk_user_url_hash) */
    private String urlHash;

    /** 展示名(下载完成后回填) */
    private String title;

    /** audio|video(D5 双形态),枚举 DubbingKind */
    private String kind;

    /** 0-init 1-running 2-completed 3-failed,枚举 DubbingTaskStatus */
    private Integer status;

    /** 当前 stage(0 起) */
    private Integer stageIndex;

    /** stage 总数(音频6/视频8,首次轮询以 engine 回包校准) */
    private Integer stageTotal;

    /** 产物大小(完成时填;video 任务为 mp4 大小) */
    private Long audioBytes;

    /** 失败原因(超长截断) */
    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
