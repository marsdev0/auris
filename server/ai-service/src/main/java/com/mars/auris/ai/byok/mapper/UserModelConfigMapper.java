package com.mars.auris.ai.byok.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.auris.ai.byok.entity.UserModelConfigDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author geyan
 * @date 2026/10/10
 */
@Mapper
public interface UserModelConfigMapper extends BaseMapper<UserModelConfigDO> {

    /**
     * 按唯一键查询(tenant_id + user_id + domain),保存/读取配置的主入口
     */
    @Select("SELECT * FROM user_model_config " +
            "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND domain = #{domain}")
    UserModelConfigDO selectByTenantUserDomain(@Param("tenantId") Long tenantId,
                                               @Param("userId") Long userId,
                                               @Param("domain") String domain);

    @Delete("DELETE FROM user_model_config " +
            "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND domain = #{domain}")
    int deleteByTenantUserDomain(@Param("tenantId") Long tenantId,
                                 @Param("userId") Long userId,
                                 @Param("domain") String domain);
}
