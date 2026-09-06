package com.mars.auris.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author geyan
 * @date 2026/9/4
 */
@Data
@TableName("user")
public class UserDO {

    /**
     * Snowflake ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * BCrypt加密密码
     */
    private String password;

    /**
     * 昵称
     */
    private String nickname;

    private String avatarUrl;

    /**
     * 0-正常 1-禁用
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    public boolean enable() {
        return status == 0;
    }

}
