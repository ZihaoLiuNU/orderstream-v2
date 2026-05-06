package com.orderstream.kafka;

import com.orderstream.event.OrderStatusChangedEvent;
import com.orderstream.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 通知服务消费者（模拟独立的 Notification Service）
 * 通过 Kafka 订阅订单状态变更事件，向用户发送推送通知
 * 与订单处理流程完全解耦：订单服务不关心通知是否成功
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "notification-service")
    public void handleOrderStatusChanged(OrderStatusChangedEvent event) {
        log.info("[通知服务] 收到事件: orderId={} {} → {}",
                event.getOrderId(), event.getPreviousStatus(), event.getNewStatus());
        // 触发通知发送（短信/推送/邮件）
        notificationService.sendOrderStatusNotification(event);
    }
}
