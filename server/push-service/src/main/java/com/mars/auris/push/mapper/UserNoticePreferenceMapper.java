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

    @Select("SELECT IFNULL(" +
            "(SELECT IF(enabled = 0, 0, channel_mask) FROM user_notice_preference WHERE user_id = #{userId} AND type = #{type}), " +
            "7)")
    Integer selectMask(@Param("userId") Long userId, @Param("type") String type);
}
