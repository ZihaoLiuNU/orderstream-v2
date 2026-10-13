package com.orderstream.order.kafka;

import com.orderstream.common.Topics;
import com.orderstream.common.event.PaymentResultEvent;
import com.orderstream.common.event.StockReservationResultEvent;
import com.orderstream.messaging.idempotency.IdempotentEventProcessor;
import com.orderstream.order.saga.OrderSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga 回执的消费入口。
 *
 * 这个类刻意保持极薄：接消息 -> 保证幂等 -> 委托给编排器。
 * 所有流程逻辑都在 OrderSagaOrchestrator 里，这样可以写纯单元测试
 * （Mockito mock 掉 repository 和 outbox），完全不需要启动 Kafka。
 *
 * 【@Transactional 必须加在【这一层】，不能只加在编排器上】
 *   事务边界要覆盖住：
 *       幂等检查 -> 业务逻辑 -> 写幂等标记 -> 写 outbox 事件
 *   四件事必须同生共死。
 *
 *   如果只在编排器方法上加事务，那么幂等标记（在这一层写的）
 *   和业务变更（在编排器里做的）就分属两个事务。
 *   在"业务提交了但标记还没提交"的窗口崩溃，重启后会重复执行业务逻辑。
 *
 * 【事务提交和 offset 提交的顺序 —— 这是理解消息可靠性的关键】
 *   两者无法原子化，实际顺序是：
 *       方法返回 -> 数据库事务提交 -> Spring Kafka 提交 offset
 *
 *   在中间崩溃：数据库已提交，offset 未提交 -> 重启后重复消费 -> 幂等表挡住。
 *   这就是为什么跨系统做不到 exactly-once，只能做到 effectively-once：
 *   投递可能重复，但【效果】只发生一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaEventListener {

    /** 消费者组 ID。同时作为 ProcessedEvent 表的 consumer 字段值 */
    private static final String CONSUMER_GROUP = "order-saga-orchestrator";

    private final OrderSagaOrchestrator orchestrator;
    private final IdempotentEventProcessor idempotentProcessor;

    @KafkaListener(topics = Topics.STOCK_RESERVATION_RESULT, groupId = CONSUMER_GROUP)
    @Transactional
    public void onStockReservationResult(StockReservationResultEvent event) {
        log.info("[Saga-1回执] sagaId={} orderId={} success={}",
                event.getSagaId(), event.getOrderId(), event.isSuccess());

        idempotentProcessor.process(CONSUMER_GROUP, event,
                e -> orchestrator.onStockReservationResult((StockReservationResultEvent) e));
    }

    @KafkaListener(topics = Topics.PAYMENT_RESULT, groupId = CONSUMER_GROUP)
    @Transactional
    public void onPaymentResult(PaymentResultEvent event) {
        log.info("[Saga-2回执] sagaId={} orderId={} success={} paymentId={}",
                event.getSagaId(), event.getOrderId(), event.isSuccess(), event.getPaymentId());

        idempotentProcessor.process(CONSUMER_GROUP, event,
                e -> orchestrator.onPaymentResult((PaymentResultEvent) e));
    }
}
