package com.orderstream.saga;

import com.orderstream.entity.Order;
import com.orderstream.event.OrderStatusChangedEvent;
import com.orderstream.event.PaymentRequestEvent;
import com.orderstream.event.PaymentResultEvent;
import com.orderstream.exception.ResourceNotFoundException;
import com.orderstream.kafka.OrderEventProducer;
import com.orderstream.kafka.PaymentEventProducer;
import com.orderstream.repository.OrderRepository;
import com.orderstream.repository.OrderSagaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Orchestration-based Saga 编排器
 *
 * 负责协调订单创建的完整分布式事务流程：
 *   1. 接收下单请求 → 创建 Saga 记录 → 发起支付请求
 *   2. 支付成功 → 更新订单为 CONFIRMED（前向恢复）
 *   3. 支付失败 → 执行补偿事务 → 取消订单（后向恢复）
 *
 * 与 Choreography-based Saga 的区别：
 *   编排器集中掌握全局状态，每一步都明确知道下一步应该做什么，
 *   出错时由编排器主动触发补偿，而不是各服务自行监听事件处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final OrderRepository orderRepository;
    private final OrderSagaRepository orderSagaRepository;
    private final PaymentEventProducer paymentEventProducer;
    private final OrderEventProducer orderEventProducer;

    /**
     * Saga 启动点：由 OrderService 在保存订单后调用
     * 创建 Saga 状态记录，发起第一个本地事务的后续步骤（支付请求）
     */
    @Transactional
    public void startSaga(Order order, BigDecimal totalAmount) {
        // 持久化 Saga 状态，作为分布式事务的"日志"
        OrderSaga saga = OrderSaga.builder()
                .orderId(order.getId())
                .state(SagaState.PAYMENT_PENDING)
                .totalAmount(totalAmount)
                .build();
        orderSagaRepository.save(saga);

        // 通过 Kafka 发起支付请求（与支付服务解耦）
        paymentEventProducer.sendPaymentRequest(
                new PaymentRequestEvent(order.getId(), order.getUser().getId(), totalAmount)
        );

        log.info("[Saga] 启动 orderId={}, 状态=PAYMENT_PENDING", order.getId());
    }

    /**
     * Saga 推进/补偿：消费支付结果事件，决定前向恢复还是后向补偿
     */
    @KafkaListener(
            topics = PaymentEventProducer.PAYMENT_RESULT_TOPIC,
            groupId = "saga-orchestrator",
            containerFactory = "sagaKafkaListenerContainerFactory"
    )
    @Transactional
    public void handlePaymentResult(PaymentResultEvent event) {
        OrderSaga saga = orderSagaRepository.findByOrderId(event.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Saga not found for orderId: " + event.getOrderId()));

        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + event.getOrderId()));

        Order.OrderStatus previousStatus = order.getStatus();

        if (event.isSuccess()) {
            // ✅ 前向恢复：支付成功，推进订单到下一状态
            saga.setState(SagaState.COMPLETED);
            order.setStatus(Order.OrderStatus.CONFIRMED);
            log.info("[Saga] 支付成功，orderId={} 推进到 CONFIRMED", order.getId());
        } else {
            // ❌ 后向补偿：支付失败，执行补偿事务取消订单
            saga.setState(SagaState.COMPENSATED);
            order.setStatus(Order.OrderStatus.CANCELLED);
            log.warn("[Saga] 支付失败（原因: {}），触发补偿事务，orderId={} 已取消",
                    event.getFailureReason(), order.getId());
        }

        orderSagaRepository.save(saga);
        orderRepository.save(order);

        // 通知下游服务（通知服务/分析服务）订单状态已变更
        orderEventProducer.sendOrderStatusChanged(new OrderStatusChangedEvent(
                order.getId(),
                order.getUser().getId(),
                previousStatus,
                order.getStatus(),
                LocalDateTime.now()
        ));
    }
}
