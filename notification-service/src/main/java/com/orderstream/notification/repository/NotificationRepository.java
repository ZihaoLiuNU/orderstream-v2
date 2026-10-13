package com.orderstream.notification.repository;

import com.orderstream.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 用户的通知列表（消息中心）。走 idx_notifications_user_created 复合索引 */
    Page<Notification> findByUserId(Long userId, Pageable pageable);

    /**
     * 找出发送失败、需要重试的通知。
     * 走 idx_notifications_retry 部分索引，只扫描 FAILED 的那几条。
     */
    List<Notification> findByStatusAndCreatedAtAfter(Notification.Status status, Instant after);
}
