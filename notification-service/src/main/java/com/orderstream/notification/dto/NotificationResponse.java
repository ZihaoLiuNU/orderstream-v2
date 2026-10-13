package com.orderstream.notification.dto;

import com.orderstream.notification.entity.Notification;
import lombok.*;
import java.time.Instant;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationResponse {

    private Long id;
    private Long orderId;
    private String title;
    private String body;
    private String status;
    private Instant createdAt;

    /**
     * 不暴露 failureReason：那是内部诊断信息，
     * 告诉用户"短信网关返回了 503"没有任何意义，
     * 反而暴露了我们用的是哪家服务商。
     */
    public static NotificationResponse from(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .orderId(n.getOrderId())
                .title(n.getTitle())
                .body(n.getBody())
                .status(n.getStatus().name())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
