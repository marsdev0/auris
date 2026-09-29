package com.mars.auris.push.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.auris.push.entity.DeliveryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author geyan
 * @date 2026/9/15
 */
@Mapper
public interface DeliveryMapper extends BaseMapper<DeliveryDO> {

    /**
     * 成功置 sent;running(0)/sending(3) 均可闭合——重试链路(RetryScanner 认领后 status=3)也要能置 sent
     */
    @Update("UPDATE delivery SET status = 1 WHERE id = #{id} AND status IN (0, 3)")
    void markSent(@Param("id") Long id);

    /**
     * 失败置 retrying + attempt + 退避时间;条件限定在(0,3),防止迟到的失败回写覆盖已 sent/死信行
     */
    @Update("UPDATE delivery SET status = 2, attempt = #{attempt}, next_retry_at = #{nextRetryAt} "
            + "WHERE id = #{id} AND status IN (0, 3)")
    void markRetry(@Param("id") Long id, @Param("attempt") int attempt,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * 到点待重试的候选(2-retrying 且已到期且未耗尽)
     * 时间由 Java 传入(JVM UTC),与 markRetry 写入同源——不用 DB 的 NOW(),避开时区错位
     */
    @Select("SELECT * FROM delivery WHERE status = 2 AND next_retry_at <= #{now} "
            + "AND attempt < #{maxAttempt} LIMIT #{limit}")
    List<DeliveryDO> selectDueRetries(@Param("now") LocalDateTime now, @Param("maxAttempt") int maxAttempt,
                                      @Param("limit") int limit);

    /**
     * 重试耗尽的死信候选(仍处 2-retrying,等判死)
     */
    @Select("SELECT * FROM delivery WHERE status = 2 AND attempt >= #{maxAttempt} LIMIT #{limit}")
    List<DeliveryDO> selectExhausted(@Param("maxAttempt") int maxAttempt, @Param("limit") int limit);

    /**
     * CAS 认领 2→3 并把租约推到 leaseUntil;影响行数=1 才算本实例抢到(两实例并发只成一个)
     */
    @Update("UPDATE delivery SET status = 3, next_retry_at = #{leaseUntil} WHERE id = #{id} AND status = 2")
    int claimSending(@Param("id") Long id, @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * CAS 判死 2→4(进死信)
     */
    @Update("UPDATE delivery SET status = 4 WHERE id = #{id} AND status = 2")
    int claimDead(@Param("id") Long id);

    /**
     * 租约过期的 sending 行拨回 retrying——认领实例崩溃/发送失败的恢复路径
     * (无条件下拨回是幂等的:拨回后各行再走 claimSending 重新抢)
     */
    @Update("UPDATE delivery SET status = 2 WHERE status = 3 AND next_retry_at <= #{now}")
    int reclaimExpiredLeases(@Param("now") LocalDateTime now);
}
