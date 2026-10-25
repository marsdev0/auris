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
     * Username
     */
    private String username;

    /**
     * BCrypt-hashed password
     */
    private String password;

    /**
     * Nickname
     */
    private String nickname;

    private String avatarUrl;

    /**
     * 0-normal 1-disabled
     */
    private Integer status;

    /**
     * Created at
     */
    private LocalDateTime createdAt;

    /**
     * Updated at
     */
    private LocalDateTime updatedAt;

    public boolean enable() {
        return status == 0;
    }

}
