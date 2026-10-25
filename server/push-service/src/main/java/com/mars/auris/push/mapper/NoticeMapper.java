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
     * Mark single read: the WHERE carries user_id (IDOR protection — others guessing your id can't modify your notification) + read_flag=0
     * (repeated mark-read is a no-op, affected rows=0, the counter won't double-DECR)
     *
     * @return affected rows, 1 = unread→read transition
     */
    @Update("UPDATE notice SET read_flag = 1 WHERE id = #{id} AND user_id = #{userId} AND read_flag = 0")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Mark all read: hit count is used by the caller to trigger a counter reset
     */
    @Update("UPDATE notice SET read_flag = 1 WHERE user_id = #{userId} AND read_flag = 0")
    int markAllRead(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM notice WHERE user_id = #{userId} AND read_flag = 0")
    long countUnread(Long userId);
}
