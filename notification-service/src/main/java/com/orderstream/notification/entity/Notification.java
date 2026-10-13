package com.orderstream.notification.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 通知记录。
 *
 * 【为什么要把通知落库，而不是发完就算】
 *   1) 可查：用户说"我没收到通知"时，能回答"我们什么时候、发了什么、成功没有"
 *   2) 可重试：发送失败的记录留在库里，定时任务可以重试
 *   3) 站内信：很多 App 有"消息中心"，本质就是读这张表
 *   4) 可审计：在某些行业（金融、医疗），"通知过用户"本身是合规要求，
 *      必须有证据
 *
 *   "发了就忘"(fire and forget) 的实现看起来简单，
 *   但一旦线上出现"用户说没收到"，你会完全没有排查手段。
 */
@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public enum Channel { PUSH, SMS, EMAIL }

    public enum Status { PENDING, SENT, FAILED }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
