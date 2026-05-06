package com.orderstream.kafka;

import com.orderstream.event.PaymentRequestEvent;
import com.orderstream.event.PaymentResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 模拟支付微服务（在真实架构中，这是独立部署的 Payment Service）
 * 在单体应用中通过 Kafka 保持与订单服务的逻辑解耦，演示 Saga 跨服务通信
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSimulator {

    private final PaymentEventProducer paymentEventProducer;
    private final Random random = new Random();

    // 监听支付请求 Topic，独立消费者组模拟独立服务
    @KafkaListener(
            topics = PaymentEventProducer.PAYMENT_REQUEST_TOPIC,
            groupId = "payment-service",
            containerFactory = "paymentKafkaListenerContainerFactory"
    )
    public void processPayment(PaymentRequestEvent event) {
        log.info("[支付模拟器] 处理订单 {} 的支付请求，金额: {}", event.getOrderId(), event.getTotalAmount());

        // 模拟支付处理耗时（网络延迟 + 银行处理时间）
        simulateProcessingDelay();

        // 模拟 90% 支付成功率
        boolean success = random.nextDouble() < 0.9;
        String failureReason = success ? null : "账户余额不足，支付被拒绝";

        // 发布支付结果，触发 Saga 编排器的下一步
        paymentEventProducer.sendPaymentResult(
                new PaymentResultEvent(event.getOrderId(), success, failureReason)
        );
    }

    private void simulateProcessingDelay() {
        try {
            // 模拟 100~500ms 的支付处理延迟
            Thread.sleep(100 + random.nextInt(400));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
