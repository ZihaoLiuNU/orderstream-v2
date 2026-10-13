package com.orderstream.payment.kafka;

import com.orderstream.common.Topics;
import com.orderstream.common.event.PaymentRefundRequestedEvent;
import com.orderstream.common.event.PaymentRequestedEvent;
import com.orderstream.messaging.idempotency.IdempotentEventProcessor;
import com.orderstream.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment-service 作为 Saga 参与者的入口。
 *
 * 结构和 menu-service 的 StockEventListener 完全一致 —— 这种一致性是刻意的：
 * 所有 Saga 参与者都遵循同一个模式（接消息 -> 幂等 -> 委托 Service），
 * 读懂一个就读懂了全部，将来新增参与者也不用重新设计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private static final String CONSUMER_GROUP = "payment-service";

    private final PaymentService paymentService;
    private final IdempotentEventProcessor idempotentProcessor;

    /** Saga 步骤 2：扣款 */
    @KafkaListener(topics = Topics.PAYMENT_REQUESTED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onPaymentRequested(PaymentRequestedEvent event) {
        log.info("[Saga-2] 收到支付请求 sagaId={} orderId={} 金额={}",
                event.getSagaId(), event.getOrderId(), event.getAmount());

        idempotentProcessor.process(CONSUMER_GROUP, event,
                e -> paymentService.processPayment((PaymentRequestedEvent) e));
    }

    /** 补偿事务 2：退款 */
    @KafkaListener(topics = Topics.PAYMENT_REFUND_REQUESTED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onRefundRequested(PaymentRefundRequestedEvent event) {
        log.info("[Saga-补偿] 收到退款请求 sagaId={} orderId={} paymentId={}",
                event.getSagaId(), event.getOrderId(), event.getPaymentId());

        idempotentProcessor.process(CONSUMER_GROUP, event,
                e -> paymentService.processRefund((PaymentRefundRequestedEvent) e));
    }
}
