package com.mars.auris.push.job;

import com.mars.auris.push.entity.DeliveryDO;
import com.mars.auris.push.mapper.DeliveryMapper;
import com.mars.auris.push.model.DeliveryTask;
import com.mars.auris.push.producer.DeliveryProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [Core layer · rewritten by you] DB-driven retry: a delay queue the delivery table builds for itself.
 * <p>
 * The original implementation was transcribed from the design doc and has been cleared — this is interview-core deep-dive A; close the doc and write it yourself first,
 * check the vault only if stuck: 03-project/01-auris/03-control-service/P5-通知服务方案.md (Chinese doc title) §1.5 "RetryScanner" and §A5.
 * <p>
 * Requirements only, no answers:
 * 1. Kafka has no delayed messages; "retry in N minutes" after a channel delivery failure must be self-built —
 * delivery table status=2 (backoff) + next_retry_at is that "deliver another day" delay queue; this Job turns it over every 30s;
 * 2. Multi-instance safety: two push-service instances will both scan the same row in the same round — each must not send once; use claim (CAS) semantics;
 * 3. Rows whose claimer then crashed must self-heal (hint: the claim pushes next_retry_at into the future — how does it double as a lease?);
 * 4. attempt >= MAX_ATTEMPT pronounces death: set FAILED_DQ + broadcast auris-notify-dlq (row not deleted; ops requeue can rescue it).
 * <p>
 * Raw material: DeliveryMapper's selectDueRetries / claimSending / claimDead / reclaimExpiredLeases
 * — four primitives ready. Read their WHERE clauses one by one before writing; the claim semantics live entirely in there.
 * Oral acceptance: after writing, explain for 5 minutes without looking at code (why DB-scan instead of Kafka redelivery? What does CAS guard? What does the lease guard?)
 *
 * @author geyan
 * @date 2026/9/16
 */
@Slf4j
@Component
public class RetryScanner {

    /**
     * attempt>=5 pronounces death (backoff 1m/2m/4m/8m, 5 attempts total)
     */
    private static final int MAX_ATTEMPT = 5;
    /**
     * Per-scan batch cap, prevents one round from grabbing too many and hammering the channel
     */
    private static final int BATCH_LIMIT = 100;
    /**
     * Claim lease: sending rows that exceed this without reaching a terminal state are treated as a crashed claimer, flipped back to backoff
     */
    private static final Duration LEASE = Duration.ofSeconds(60);

    /**
     * First-delivery orphan window: rows still pending(0) after this duration since insert are treated as never-delivered.
     * >> Normal first delivery takes seconds; same order of magnitude as the 60s lease
     */
    private static final Duration UNSENT_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private DeliveryProducer deliveryProducer;

    @Scheduled(fixedDelay = 30_000)
    public void scan() {
        // Think: does the call order of the three steps below matter? Why must lease reclamation run before due-retry?
        reclaimExpiredLeases();  // 3-2：租约过期，认领方已死
        reclaimUnsent();  // 0-2：首发孤儿，消息从未送达
        retryDue();  // 2-3：到点认领重发
        deadLetter();  // 2-4：耗尽判死
    }

    /**
     * ④ First-delivery orphan reclaim: pending(0) rows whose updated_at exceeds the window are flipped back to 2 (backoff).
     * Covers every breakage where "insert succeeded but the delivery message never arrived" (route exception midway / broker long-down / process crash) —
     * normal first delivery closes in seconds; still 0 after the window = the message likely never arrived; flip to 2 and go through normal claim-resend.
     * Same as ③: the logic lives in the mapper's WHERE; the Java side only triggers and counts.
     */
    private void reclaimUnsent() {
        LocalDateTime now = LocalDateTime.now();
        int reclaimed = deliveryMapper.reclaimUnsent(now, now.minus(UNSENT_TIMEOUT));
        if (reclaimed > 0) {
            // Normally 0 long-term; a spike = batch breakage in the first-delivery path (broker long-down / instance crash) — the self-healing mechanism at work
            log.warn("reclaimed {} unsent deliveries", reclaimed);
        }
    }

    /**
     * ③ Lease reclaim: rows with status=3 (sending) whose next_retry_at has expired are flipped back to 2 (backoff).
     * All the logic is in the mapper's WHERE — on claim, the lease (now+60s) was written into next_retry_at;
     * expiry means "the claimer has been silent for 60s, presumed dead"; the Java side only triggers and counts.
     * Time comes from Java's now (JVM UTC, same source as claimSending's write; not DB NOW() to avoid timezone skew).
     */
    private void reclaimExpiredLeases() {
        int reclaimed = deliveryMapper.reclaimExpiredLeases(LocalDateTime.now());
        if (reclaimed > 0) {
            // Normally 0 long-term; a sudden jump = an instance crashed/hung after claiming — the self-healing mechanism at work
            log.warn("reclaimed {} expired-lease deliveries", reclaimed);
        }
    }

    /**
     * ① Due retry: only send back to the original channel topic after a successful claim (2→3); attempt passes through unchanged
     */
    private void retryDue() {
        // Core layer: why can't it be "query then send"?
        //                   Which topic to send back to? What key for the message (think partition ordering)? Why doesn't attempt increment here?

        List<DeliveryDO> deliveryDOS = deliveryMapper.selectDueRetries(LocalDateTime.now(), MAX_ATTEMPT, BATCH_LIMIT);
        if (CollectionUtils.isEmpty(deliveryDOS)) {
            return;
        }
        for (DeliveryDO item : deliveryDOS) {
            // Claim: CAS 2→3; affected rows=1 means this instance won (SELECT doesn't lock rows; two instances scan the same batch in the same round)
            int result = deliveryMapper.claimSending(item.getId(), LocalDateTime.now().plus(LEASE));
            if (result > 0) {
                try {
                    deliveryProducer.sendDelivery(DeliveryTask.builder()
                            .deliveryId(String.valueOf(item.getId()))
                            .noticeId(String.valueOf(item.getNotificationId()))
                            .channel(item.getChannel())
                            .attempt(item.getAttempt()).build());
                } catch (Exception e) {
                    // No rollback, no compensation: the row is 3 with a 60s lease; reclaimExpiredLeases reaps and reschedules on expiry.
                    // The catch is for isolation only — don't let one row's send exception interrupt the whole batch (remaining rows are still 2, next round picks them up, just one round slower)
                    log.error("重投发送失败,等待租约回收自愈: deliveryId={}", item.getId(), e);
                }
            }
        }
    }

    /**
     * ② Dead letter: retry exhausted → pronounce death (2→4) + auris-notify-dlq broadcast
     */
    private void deadLetter() {
        // Core layer: how do the death-candidate and retry-candidate query conditions differ? Why not delete the row after death?
        List<DeliveryDO> deliveryDOS = deliveryMapper.selectExhausted(MAX_ATTEMPT, BATCH_LIMIT);
        if (CollectionUtils.isEmpty(deliveryDOS)) {
            return;
        }
        for (DeliveryDO item : deliveryDOS) {
            // As above, always think multi-instance: only the one instance whose update wins sends the topic
            int result = deliveryMapper.claimDead(item.getId());
            if (result > 0) {
                deliveryProducer.sendDlq(DeliveryTask.builder()
                        .deliveryId(String.valueOf(item.getId()))
                        .noticeId(String.valueOf(item.getNotificationId()))
                        .channel(item.getChannel())
                        .attempt(item.getAttempt()).build());
            }
        }
    }
}
