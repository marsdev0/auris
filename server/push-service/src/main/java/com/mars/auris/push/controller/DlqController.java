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
 * 死信运维接口(P5 §1.5 ⑥ / §4):查询走 DB(topic 只是广播位),requeue 复活。
 * TODO 挂账(P5 §2.10):加内部 token 认证,当前先复用用户 JWT 挡住匿名访问
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
     * 死信列表(样板层):查 status=FAILED_DQ 的投递记录
     */
    @GetMapping
    public ApiResponse<List<DeliveryDO>> list() {
        return ApiResponse.ok(deliveryMapper.selectList(new QueryWrapper<DeliveryDO>()
                .eq("status", Delivery.FAILED_DQ.getCode())
                .orderByDesc("updated_at")
                .last("LIMIT 50")));
    }

    /**
     * 死信重投:把一条 failed_dq 的投递复活(4→2, attempt=0, next_retry_at=now)
     */
    @PostMapping("/{id}/requeue")
    public ApiResponse<Boolean> requeue(@PathVariable("id") Long id) {
        // 决策(P5 §1.5 ⑥ 基线的偏差,偏差记 §9):只拨状态、不发渠道 topic——
        // 直发时行 status=2,Worker 的 markSent WHERE IN (0,3) 闭合不了,且 30s 内 Scanner
        // 还会再捞再发(双发);拨回 2 后下一轮(≤30s)RetryScanner 认领重发,闭合顺畅。
        // WHERE 限定 id + status=4 即 CAS:重复点击/并发 requeue 只有第一次生效,天然幂等。
        int requeue = deliveryMapper.requeue(id, LocalDateTime.now());
        return ApiResponse.ok(requeue > 0);
    }
}
