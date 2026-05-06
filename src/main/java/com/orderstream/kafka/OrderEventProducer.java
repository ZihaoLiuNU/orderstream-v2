package com.orderstream.kafka;

import com.orderstream.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    // 订单状态变更事件 Topic，下游通知服务和分析服务均订阅此 Topic
    public static final String TOPIC = "order-status-changed";

    // 使用 Object 泛型以兼容多种事件类型，JsonSerializer 保证序列化正确性
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendOrderStatusChanged(OrderStatusChangedEvent event) {
        // 以 orderId 为 partition key，保证同一订单的事件顺序写入同一分区
        kafkaTemplate.send(TOPIC, String.valueOf(event.getOrderId()), event);
        log.info("[事件] 订单 {} 状态变更: {} → {}", event.getOrderId(),
                event.getPreviousStatus(), event.getNewStatus());
    }
}
