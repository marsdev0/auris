package com.mars.auris.push.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.auris.push.entity.NoticeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Mapper
public interface NoticeMapper extends BaseMapper<NoticeDO> {

    /**
     * 单条已读:条件带 user_id(IDOR 防护——别人猜 id 也改不到你的通知)+ read_flag=0
     * (重复点已读是 no-op,影响行数=0,计数器不会重复 DECR)
     *
     * @return 影响行数,1=本次从未读变已读
     */
    @Update("UPDATE notice SET read_flag = 1 WHERE id = #{id} AND user_id = #{userId} AND read_flag = 0")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 全部已读:命中条数由调用方用来触发计数器 reset
     */
    @Update("UPDATE notice SET read_flag = 1 WHERE user_id = #{userId} AND read_flag = 0")
    int markAllRead(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM notice WHERE user_id = #{userId} AND read_flag = 0")
    long countUnread(Long userId);
}
