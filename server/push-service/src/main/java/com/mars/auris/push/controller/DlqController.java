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
     * 【核心层 · 由你写】死信重投:把一条 failed_dq 的投递复活
     */
    @PostMapping("/{id}/requeue")
    public ApiResponse<Boolean> requeue(@PathVariable("id") Long id) {
        // TODO@geyan 核心层:状态迁移 4(FAILED_DQ) → 2(RETRYING),attempt 清零,
        //  next_retry_at 置为"立刻可重试",再把投递消息发回原渠道 topic(auris-notify-delivery-{channel})。
        //  要点:
        //  1. DeliveryMapper 还没有 requeue 方法——自己加,注意 WHERE 条件为什么要限定 status=4(CAS);
        //  2. 发回 topic 时 attempt 传什么?和 RetryScanner.retryDue 的重投消息有什么区别?
        //  3. 想想:requeue 之后,这条消息是谁捞起来发的(RetryScanner 还是渠道 Worker)?
        //  设计参考:P5 方案 §1.5 ⑥。
        throw new UnsupportedOperationException("TODO@geyan: requeue 未实现");
    }
}
