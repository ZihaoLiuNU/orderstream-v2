package com.orderstream.kafka;

import com.orderstream.event.PaymentRequestEvent;
import com.orderstream.event.PaymentResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    // Saga 事件总线中的两个 Topic
    public static final String PAYMENT_REQUEST_TOPIC = "payment-requests";
    public static final String PAYMENT_RESULT_TOPIC = "payment-results";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // 发布支付请求（Saga 第1步：订单服务 → 支付服务）
    public void sendPaymentRequest(PaymentRequestEvent event) {
        kafkaTemplate.send(PAYMENT_REQUEST_TOPIC, String.valueOf(event.getOrderId()), event);
        log.info("[Saga-支付请求] orderId={}, amount={}", event.getOrderId(), event.getTotalAmount());
    }

    // 发布支付结果（Saga 第2步：支付服务 → Saga 编排器）
    public void sendPaymentResult(PaymentResultEvent event) {
        kafkaTemplate.send(PAYMENT_RESULT_TOPIC, String.valueOf(event.getOrderId()), event);
        log.info("[Saga-支付结果] orderId={}, success={}, reason={}",
                event.getOrderId(), event.isSuccess(), event.getFailureReason());
    }
}
