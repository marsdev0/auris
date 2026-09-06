package com.mars.auris.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.auris.user.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
