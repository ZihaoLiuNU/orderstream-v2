package com.orderstream.notification.controller;

import com.orderstream.common.security.CurrentUser;
import com.orderstream.notification.dto.NotificationResponse;
import com.orderstream.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 消息中心：用户查自己的通知历史。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    /**
     * 只有一个接口，而且查询条件里就锁死了 userId。
     *
     * 【这是防 BOLA 最干净的写法】：
     *   不提供"按通知 ID 查单条"的接口，查询本身就只能查到自己的。
     *   结构上不存在越权的可能，不需要任何事后判断，
     *   也就不可能因为某个调用方忘了判断而出漏洞。
     *
     *   能用查询条件表达的授权，就不要用事后校验来实现 ——
     *   前者是编译期就确定的，后者依赖每个人都记得写。
     */
    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Transactional(readOnly = true)
    public Page<NotificationResponse> myNotifications(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        CurrentUser user = CurrentUser.get();
        return notificationRepository.findByUserId(user.getUserId(), pageable)
                .map(NotificationResponse::from);
    }
}
