package com.mars.auris.push.common;

import lombok.Getter;

/**
 * Channel delivery state machine (delivery.status column, 0-4).
 * <p>
 * The status marks not "where the message is in the Kafka pipeline" (the DB cannot see inside the pipeline — whether a message was sent
 * or consumed, the table has no way to know), but this row's current bookkeeping fact: <b>who owns it, what it's waiting for</b>.
 * <p>
 * Two paths: first-time (Router inserts a PENDING placeholder → sends to channel topic → Worker closes SENT/BACKOFF directly);
 * retry (RetryScanner claims BACKOFF→SENDING when due → redeliver → Worker closes; on failure, back off again).
 * <p>
 * Every non-terminal state has a patrol guard; no state is left unattended:
 * <ul>
 *   <li>PENDING(0) → reclaimUnsent: still 0 after 2 minutes = first message never delivered, flip to 2 and reschedule</li>
 *   <li>BACKOFF(2) → selectDueRetries: backoff due, claim and resend</li>
 *   <li>SENDING(3) → reclaimExpiredLeases: 60s lease expired without write-back = claimer is dead, reap and flip to 2</li>
 *   <li>FAILED_DQ(4)→ manual requeue (4→2, attempt reset to zero)</li>
 * </ul>
 * <p>
 * Plain-language mapping: just ordered / rescheduled for another day / carrier has accepted / delivered / lost-package claim.
 * <p>
 * code is persistence semantics: existing rows, mapper SQL literals, and DDL comments are all bound to the numbers; reordering the enum is a data migration
 * (same lesson as {@link Channel}'s bits) — names are for humans and can change anytime; codes are for machines and must not move.
 *
 * @author geyan
 * @date 2026/9/16
 */
@Getter
public enum Delivery {

    /**
     * 0-placeholder, unowned, waiting for first delivery.
     * <p>
     * In: written on Router insert — the point is to occupy the uk_notify_channel idempotency key first; the Kafka message hasn't been sent yet.
     * Out: closed by Worker's markSent/markRetry on first consume (the first-time path needs no DB claim; consumer groups are naturally exclusive);
     * if the message never arrives (route broke midway / broker long-down / process crash), reclaimUnsent flips it to 2 after 2 minutes.
     */
    PENDING(0),

    /**
     * 1-terminal, delivered by the channel. markSent (0/3→1) after the channel call succeeds; irreversible.
     */
    SENT(1),

    /**
     * 2-backoff wait, unowned, waiting for the alarm.
     * <p>
     * In: written on Worker's markRetry (attempt+1, next_retry_at=backoff time).
     * Nothing is happening right now — the row waits in the table until time is up; it's not "retrying", it's "retry scheduled".
     * next_retry_at is the alarm clock and also selectDueRetries' scan key.
     * Out: when due, RetryScanner claims (2→3) and resends; attempt>=5 gets pronounced dead by selectExhausted (2→4).
     * Formerly RETRYING; the progressive aspect collided with SENDING's semantics, renamed (2026-10-02).
     */
    BACKOFF(2),

    /**
     * 3-in-flight, owned (the claiming instance holds the lease), waiting for the result.
     * <p>
     * In: CAS-written on RetryScanner's claimSending (2→3; lease now+60s conveniently lands in next_retry_at —
     * this column is the backoff alarm in state 2, rewritten as the lease deadline in state 3).
     * Coverage window: from claim to Worker write-back, spanning "before sending to Kafka → message queued in topic → Worker calling the channel"
     * — essentially an ownership marker (a distributed lock): multi-instance races via CAS; only the one affecting rows=1 may deliver.
     * Out: Worker's markSent/markRetry writes back; an expired lease without write-back gets reaped by reclaimExpiredLeases (3→2).
     */
    SENDING(3),

    /**
     * 4-terminal, dead letter. Retry exhaustion (attempt>=5) pronounced dead by claimDead (2→4) + auris-notify-dlq broadcast for alerting.
     * The row is not deleted; ops POST /v1/notification/dlq/{id}/requeue revives it (4→2, attempt reset, re-enters the Scanner).
     */
    FAILED_DQ(4);

    private final int code;

    Delivery(int code) {
        this.code = code;
    }
}
