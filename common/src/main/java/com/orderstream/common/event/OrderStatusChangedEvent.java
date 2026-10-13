package com.orderstream.common.event;

import lombok.*;
import java.time.Instant;

/**
 * 领域事件广播：订单状态变了。
 *
 * 和前面几个事件的本质区别：
 *   StockReservationRequested / PaymentRequested 是【命令】—— 编排器点名要求某个服务做事，
 *   期待一个回执，接收方唯一。
 *   OrderStatusChanged 是【事件】—— 陈述一个已发生的事实，广播出去，
 *   order-service 完全不知道也不关心谁在消费它。
 *
 * 这个区别的实际价值：将来要加 analytics-service 做实时大盘，
 * 只需要新起一个 consumer group 订阅这个 topic，order-service 一行代码都不用改。
 * 这就是「用 Kafka 解耦下游」的真正含义 —— 不是「用了消息队列」，
 * 而是「下游的增减不会反向影响上游」。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderStatusChangedEvent implements DomainEvent {
    private String eventId;
    private Instant occurredAt;

    private Long orderId;
    private Long userId;
    private String previousStatus;
    private String newStatus;
    /** 状态变更的原因，通知文案会用到（例如取消原因） */
    private String reason;
}
