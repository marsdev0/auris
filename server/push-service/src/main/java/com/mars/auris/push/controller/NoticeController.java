package com.mars.auris.push.controller;

import com.mars.auris.common.auth.UserPrincipal;
import com.mars.auris.common.rsp.ApiResponse;
import com.mars.auris.push.cache.UnreadCounter;
import com.mars.auris.push.entity.NoticeDO;
import com.mars.auris.push.service.NoticeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知读取(用户视角闭环:P5 §4 接口契约)
 * 前端:红点 30s 轮询 unread-count;列表页;单条/全部已读
 *
 * @author geyan
 * @date 2026/9/28
 */
@RestController
@RequestMapping("/v1/notification")
public class NoticeController {

    @Autowired
    private NoticeService noticeService;
    @Autowired
    private UnreadCounter unreadCounter;

    @GetMapping
    public ApiResponse<List<NoticeDO>> list(@AuthenticationPrincipal UserPrincipal user,
                                            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(noticeService.listByUser(user.userId(), Math.min(limit, 100)));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(unreadCounter.get(user.userId()));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Boolean> read(@AuthenticationPrincipal UserPrincipal user,
                                     @PathVariable("id") Long id) {
        return ApiResponse.ok(noticeService.markRead(user.userId(), id));
    }

    @PutMapping("/read-all")
    public ApiResponse<Integer> readAll(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(noticeService.markAllRead(user.userId()));
    }
}
