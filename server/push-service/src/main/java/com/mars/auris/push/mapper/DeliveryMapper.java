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
     * Set sent; both pending(0) and sending(3) can close — the retry path (claimed by RetryScanner, status=3) must also be able to reach sent
     */
    @Update("UPDATE delivery SET status = 1 WHERE id = #{id} AND status IN (0, 3)")
    void markSent(@Param("id") Long id);

    /**
     * Set backoff + attempt + backoff time on failure; the WHERE limits to (0,3), preventing a late failure write-back from clobbering a sent/dead-letter row
     */
    @Update("UPDATE delivery SET status = 2, attempt = #{attempt}, next_retry_at = #{nextRetryAt} "
            + "WHERE id = #{id} AND status IN (0, 3)")
    void markRetry(@Param("id") Long id, @Param("attempt") int attempt,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * Due-retry candidates (2-backoff, expired, not exhausted)
     * Time comes from Java (JVM UTC), same source as markRetry's write — not DB NOW(), avoiding timezone skew
     */
    @Select("SELECT * FROM delivery WHERE status = 2 AND next_retry_at <= #{now} "
            + "AND attempt < #{maxAttempt} LIMIT #{limit}")
    List<DeliveryDO> selectDueRetries(@Param("now") LocalDateTime now, @Param("maxAttempt") int maxAttempt,
                                      @Param("limit") int limit);

    /**
     * Dead-letter candidates with retries exhausted (still 2-backoff, awaiting death sentence)
     */
    @Select("SELECT * FROM delivery WHERE status = 2 AND attempt >= #{maxAttempt} LIMIT #{limit}")
    List<DeliveryDO> selectExhausted(@Param("maxAttempt") int maxAttempt, @Param("limit") int limit);

    /**
     * CAS claim 2→3 pushing the lease to leaseUntil; affected rows=1 means this instance won (concurrent instances, only one succeeds)
     */
    @Update("UPDATE delivery SET status = 3, next_retry_at = #{leaseUntil} WHERE id = #{id} AND status = 2")
    int claimSending(@Param("id") Long id, @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * CAS pronounce death 2→4 (into dead letter)
     */
    @Update("UPDATE delivery SET status = 4 WHERE id = #{id} AND status = 2")
    int claimDead(@Param("id") Long id);

    /**
     * Flip expired-lease sending rows back to backoff — the recovery path for a crashed claimer / failed send
     * (unconditional flip is idempotent: flipped rows go through claimSending again to re-race)
     */
    @Update("UPDATE delivery SET status = 2 WHERE status = 3 AND next_retry_at <= #{now}")
    int reclaimExpiredLeases(@Param("now") LocalDateTime now);

    @Update("UPDATE delivery SET status = 2, attempt = 0, next_retry_at = #{now} WHERE id = #{id} AND status = 4")
    int requeue(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * First-delivery orphan reclaim: pending(0) rows whose updated_at is older than cutoff are flipped back to backoff(2)
     * Covers every breakage where "insert succeeded but the delivery message never arrived" (route exception / broker long-down / process crash)
     *
     * Normal first delivery closes in seconds; still pending(0) past the window = the message likely never arrived; flip status to backoff(2)
     * and RetryScanner claims and resends normally
     */
    @Update("UPDATE delivery SET status = 2, next_retry_at = #{now} WHERE status = 0 AND updated_at <= #{cutoff}")
    int reclaimUnsent(@Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);
}
