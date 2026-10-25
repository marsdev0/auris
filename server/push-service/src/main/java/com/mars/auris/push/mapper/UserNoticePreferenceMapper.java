package com.mars.auris.push.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.auris.push.entity.UserNoticePreferenceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author geyan
 * @date 2026/9/16
 */
@Mapper
public interface UserNoticePreferenceMapper extends BaseMapper<UserNoticePreferenceDO> {

    /**
     * Fallback mask when no preference row exists: one bit per implemented channel (§9 decision 2026-10-02) —
     * 0b101=inapp+feishu (bit promoted when Feishu launched 2026-10-02); promote to 0b111 when email ships
     */
    @Select("SELECT IFNULL(" +
            "(SELECT IF(enabled = 0, 0, channel_mask) FROM user_notice_preference WHERE user_id = #{userId} AND type = #{type}), " +
            "5)")
    Integer selectMask(@Param("userId") Long userId, @Param("type") String type);
}
