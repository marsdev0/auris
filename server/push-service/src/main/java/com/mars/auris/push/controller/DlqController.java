package com.mars.auris.push.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mars.auris.common.rsp.ApiResponse;
import com.mars.auris.push.common.Delivery;
import com.mars.auris.push.entity.DeliveryDO;
import com.mars.auris.push.mapper.DeliveryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Dead-letter ops endpoints (P5 §1.5 ⑥ / §4): list reads the DB (topic is just a broadcast slot), requeue revives.
 * TODO deferred (P5 §2.10): add internal token auth; for now user JWT blocks anonymous access
 *
 * @author geyan
 * @date 2026/9/28
 */
@RestController
@RequestMapping("/v1/notification/dlq")
public class DlqController {

    @Autowired
    private DeliveryMapper deliveryMapper;

    /**
     * Dead-letter list (boilerplate tier): query delivery records with status=FAILED_DQ
     */
    @GetMapping
    public ApiResponse<List<DeliveryDO>> list() {
        return ApiResponse.ok(deliveryMapper.selectList(new QueryWrapper<DeliveryDO>()
                .eq("status", Delivery.FAILED_DQ.getCode())
                .orderByDesc("updated_at")
                .last("LIMIT 50")));
    }

    /**
     * Dead-letter requeue: revive a failed_dq delivery (4→2, attempt=0, next_retry_at=now)
     */
    @PostMapping("/{id}/requeue")
    public ApiResponse<Boolean> requeue(@PathVariable("id") Long id) {
        // Decision (a deviation from P5 §1.5 ⑥ baseline, recorded in §9): only flip the status, don't send to the channel topic —
        // with a direct send the row is status=2, Worker's markSent WHERE IN (0,3) can't close it, and within 30s the Scanner
        // would pick it up and send again (double delivery); flipping back to 2 lets the next round (≤30s) of RetryScanner claim and resend, closing cleanly.
        // WHERE scoping to id + status=4 is the CAS: repeated clicks / concurrent requeues — only the first one wins, naturally idempotent.
        int requeue = deliveryMapper.requeue(id, LocalDateTime.now());
        return ApiResponse.ok(requeue > 0);
    }
}
