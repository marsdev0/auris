package com.mars.auris.ai.transcribe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mars.auris.ai.transcribe.entity.TranscribeRecordDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author geyan
 * @date 2026/9/11
 */
@Mapper
public interface TranscribeRecordMapper extends BaseMapper<TranscribeRecordDO> {

    @Select("SELECT * FROM transcribe_record WHERE id = #{id} AND user_id = #{userId}")
    TranscribeRecordDO selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM transcribe_record WHERE user_id = #{userId} AND engine_task_id = #{engineTaskId}")
    TranscribeRecordDO selectByUserIdAndEngineTaskId(@Param("userId") Long userId,
                                                     @Param("engineTaskId") String engineTaskId);

    @Select("SELECT id, user_id, engine_task_id, source, title, duration_ms, " +
            "segments_json, status, error_msg, created_at, updated_at " +
            "FROM transcribe_record WHERE user_id = #{userId} " +
            "ORDER BY created_at DESC, id DESC")
    Page<TranscribeRecordDO> pageByUserId(Page<TranscribeRecordDO> page, @Param("userId") Long userId);

    @Update("UPDATE transcribe_record SET text = #{text}, status = 1 " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0")
    int complete(@Param("id") Long id, @Param("userId") Long userId, @Param("text") String text);

    @Update("UPDATE transcribe_record SET error_msg = #{errorMsg}, status = 2 " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0")
    int fail(@Param("id") Long id, @Param("userId") Long userId, @Param("errorMsg") String errorMsg);


    @Delete("DELETE FROM transcribe_record WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

}
