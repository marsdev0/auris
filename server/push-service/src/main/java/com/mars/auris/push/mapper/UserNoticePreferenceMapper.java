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
     * 无偏好行时的兜底掩码:渠道实现一个开一个位(§9 2026-10-02 拍板)——
     * 0b101=inapp+feishu(2026-10-02 飞书上线升位);email 上线后再升 0b111
     */
    @Select("SELECT IFNULL(" +
            "(SELECT IF(enabled = 0, 0, channel_mask) FROM user_notice_preference WHERE user_id = #{userId} AND type = #{type}), " +
            "5)")
    Integer selectMask(@Param("userId") Long userId, @Param("type") String type);
}
