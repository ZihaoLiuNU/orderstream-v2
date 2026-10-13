package com.orderstream.notification.kafka;

import com.orderstream.common.Topics;
import com.orderstream.common.event.OrderStatusChangedEvent;
import com.orderstream.messaging.idempotency.IdempotentEventProcessor;
import com.orderstream.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订阅订单状态变更事件。
 *
 * 【这是一个纯粹的事件订阅者，和 Saga 参与者有本质区别】
 *
 *   Saga 参与者（menu/payment）收到的是【命令】：
 *     编排器点名要求它做事，并且【期待一个回执】。
 *     它的成败会影响整个分布式事务的走向。
 *
 *   这个服务收到的是【事件】：
 *     order-service 只是广播了一个已发生的事实，
 *     完全不知道也不关心谁在消费、消费成功没有。
 *     通知发失败了，订单该怎样还怎样。
 *
 *   这个区别直接体现在代码上：
 *     Saga 参与者必须回发结果事件（见 StockService / PaymentService），
 *     这个服务【不回发任何东西】。
 *
 * 【consumer group 的关键作用】
 *   groupId = "notification-service"，和 cart-service 的
 *   "cart-service" 是不同的组。
 *   不同消费者组各自维护 offset，所以【同一条消息两个组都能收到一份】——
 *   这正是发布订阅模型的意义。
 *
 *   将来要加 analytics-service 做实时大盘？
 *   新起一个消费者组订阅同一个 topic 就行，order-service 一行代码都不用改。
 *   这才是"用 Kafka 解耦下游"的真正价值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private static final String CONSUMER_GROUP = "notification-service";

    private final NotificationService notificationService;
    private final IdempotentEventProcessor idempotentProcessor;

    @KafkaListener(topics = Topics.ORDER_STATUS_CHANGED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        log.debug("[通知] 收到状态变更 orderId={} {} -> {}",
                event.getOrderId(), event.getPreviousStatus(), event.getNewStatus());

        idempotentProcessor.process(CONSUMER_GROUP, event,
                e -> notificationService.handleOrderStatusChanged((OrderStatusChangedEvent) e));
    }
}
