package com.orderstream.payment.service;

import com.orderstream.common.Topics;
import com.orderstream.common.event.DomainEvent;
import com.orderstream.common.event.PaymentRefundRequestedEvent;
import com.orderstream.common.event.PaymentRequestedEvent;
import com.orderstream.common.event.PaymentResultEvent;
import com.orderstream.messaging.outbox.OutboxPublisher;
import com.orderstream.payment.entity.Payment;
import com.orderstream.payment.entity.Refund;
import com.orderstream.payment.repository.PaymentRepository;
import com.orderstream.payment.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付服务测试。
 *
 * ============================================================================
 * 【这组测试守护的是"绝不重复扣款"这个最不能出错的保证】
 * ============================================================================
 *
 * 重复扣款是那种"技术上是小 bug、业务上是重大事故"的问题：
 *   代码可能只是少了一个 if，但后果是用户信任崩塌、客服被投诉淹没、
 *   可能还要面对监管。
 *
 * 所以这里的每个测试都对应一个具体的失败场景，不是为了凑覆盖率。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private OutboxPublisher outboxPublisher;

    @InjectMocks private PaymentService paymentService;

    private static final String SAGA_ID = "saga-xyz";
    private static final Long ORDER_ID = 200L;

    @BeforeEach
    void setUp() {
        // @Value 注入的字段在单元测试里不会被填充，需要手动设。
        // 延迟设成 0 让测试瞬间跑完 —— 单元测试里没有理由真的去 sleep
        ReflectionTestUtils.setField(paymentService, "simulatedLatencyMs", 0);
        // 失败率设成 0 让测试结果确定。
        // 【随机性是单元测试的大敌】：一个偶发失败的测试会被人加 @Disabled，
        // 然后这块逻辑就再也没被测过了
        ReflectionTestUtils.setField(paymentService, "failureRate", 0.0);
    }

    private PaymentRequestedEvent request() {
        return PaymentRequestedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .orderId(ORDER_ID).sagaId(SAGA_ID).userId(42L)
                .amount(new BigDecimal("29.97"))
                .idempotencyKey(SAGA_ID)
                .build();
    }

    // ======================================================================
    @Nested
    @DisplayName("扣款")
    class ProcessPayment {

        @Test
        @DisplayName("首次请求 -> 创建支付记录并回报成功")
        void firstRequestCreatesPayment() {
            when(paymentRepository.findByIdempotencyKey(SAGA_ID)).thenReturn(Optional.empty());
            when(paymentRepository.save(any())).thenAnswer(inv -> {
                Payment p = inv.getArgument(0);
                p.setId(777L);
                return p;
            });

            paymentService.processPayment(request());

            ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(Payment.Status.SUCCEEDED);
            assertThat(saved.getValue().getIdempotencyKey()).isEqualTo(SAGA_ID);
            // 外部交易号必须有 —— 它是退款时定位原交易的唯一依据
            assertThat(saved.getValue().getExternalTxnId()).isNotBlank();

            ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.PAYMENT_RESULT), eq("200"), evt.capture());
            PaymentResultEvent result = (PaymentResultEvent) evt.getValue();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getPaymentId()).isEqualTo(777L);
        }

        /**
         * 【幂等的核心测试：重复请求绝不能重复扣款】
         *
         * Kafka 是 at-least-once，这条消息重复投递是【必然】的，不是异常：
         *   - 消费者处理成功后、提交 offset 前崩溃
         *   - 消费者超时被踢出组，分区重新分配
         *   - Outbox relay 发送成功但标记 sent_at 前崩溃
         *
         * 只要有一次没挡住，用户就被扣了两次钱。
         */
        @Test
        @DisplayName("重复请求 -> 不创建新记录，绝不重复扣款")
        void duplicateRequestDoesNotChargeAgain() {
            Payment existing = Payment.builder()
                    .id(777L).orderId(ORDER_ID).userId(42L)
                    .amount(new BigDecimal("29.97"))
                    .idempotencyKey(SAGA_ID)
                    .status(Payment.Status.SUCCEEDED)
                    .build();
            when(paymentRepository.findByIdempotencyKey(SAGA_ID)).thenReturn(Optional.of(existing));

            paymentService.processPayment(request());

            // 【核心断言】：没有任何新的写入
            verify(paymentRepository, never()).save(any());
        }

        /**
         * 【幂等不只是"不重复执行副作用"，还包括"重复请求要给出相同的响应"】
         *
         * 这一点最容易被漏掉。如果重复请求只是静默 return 什么都不做：
         *
         *   第一次支付成功了，但结果消息在投递途中丢了（或编排器没收到）
         *   -> order-service 超时后重发支付请求
         *   -> payment-service 一看"处理过了"，直接 return
         *   -> order-service 永远等不到回执，订单永久卡死
         *
         * 重发原结果就打破了这个死局：
         * 无论请求重复多少次，调用方总能拿到一个确定的答复。
         */
        @Test
        @DisplayName("重复请求 -> 必须重发原结果，否则编排器会永远等待")
        void duplicateRequestRepublishesOriginalResult() {
            Payment existing = Payment.builder()
                    .id(777L).orderId(ORDER_ID).userId(42L)
                    .amount(new BigDecimal("29.97"))
                    .idempotencyKey(SAGA_ID)
                    .status(Payment.Status.SUCCEEDED)
                    .build();
            when(paymentRepository.findByIdempotencyKey(SAGA_ID)).thenReturn(Optional.of(existing));

            paymentService.processPayment(request());

            ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.PAYMENT_RESULT), eq("200"), evt.capture());
            PaymentResultEvent result = (PaymentResultEvent) evt.getValue();
            assertThat(result.isSuccess()).isTrue();
            // 必须是【原来那笔】支付的 ID，不是新生成的
            assertThat(result.getPaymentId()).isEqualTo(777L);
        }

        @Test
        @DisplayName("支付失败 -> 记录失败原因并回报失败")
        void failedPaymentIsReported() {
            ReflectionTestUtils.setField(paymentService, "failureRate", 1.0);   // 强制失败
            when(paymentRepository.findByIdempotencyKey(SAGA_ID)).thenReturn(Optional.empty());
            when(paymentRepository.save(any())).thenAnswer(inv -> {
                Payment p = inv.getArgument(0);
                p.setId(888L);
                return p;
            });

            paymentService.processPayment(request());

            ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.PAYMENT_RESULT), any(), evt.capture());
            PaymentResultEvent result = (PaymentResultEvent) evt.getValue();
            assertThat(result.isSuccess()).isFalse();
            // 失败原因会一路传到用户看到的取消原因里，必须可读
            assertThat(result.getFailureReason()).isNotBlank();
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("退款（补偿事务）")
    class ProcessRefund {

        private PaymentRefundRequestedEvent refundRequest() {
            return PaymentRefundRequestedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .occurredAt(Instant.now())
                    .orderId(ORDER_ID).sagaId(SAGA_ID).paymentId(777L)
                    .reason("用户主动取消")
                    .build();
        }

        /**
         * 【退款是一笔新的、方向相反的交易，不是把原记录改掉或删掉】
         *
         * 这是财务系统的铁律（append-only ledger）：
         *   审计要能回答"钱什么时候收的、什么时候退的"；
         *   部分退款需要多条记录；
         *   和支付网关对账比对的是【流水】，不是当前状态。
         *
         * 所以这个测试断言的是：新增了 Refund 记录，
         * 且原 Payment 的金额没有被动过。
         */
        @Test
        @DisplayName("退款 -> 追加退款流水，原支付记录金额不变")
        void refundAppendsLedgerEntry() {
            Payment payment = Payment.builder()
                    .id(777L).orderId(ORDER_ID).userId(42L)
                    .amount(new BigDecimal("29.97"))
                    .idempotencyKey(SAGA_ID)
                    .status(Payment.Status.SUCCEEDED)
                    .build();
            when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(paymentRepository.findById(777L)).thenReturn(Optional.of(payment));

            paymentService.processRefund(refundRequest());

            ArgumentCaptor<Refund> refund = ArgumentCaptor.forClass(Refund.class);
            verify(refundRepository).save(refund.capture());
            assertThat(refund.getValue().getAmount()).isEqualByComparingTo("29.97");
            assertThat(refund.getValue().getPaymentId()).isEqualTo(777L);

            // 原支付记录只改状态标记，金额保持不变 —— 历史事实不可篡改
            assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
            assertThat(payment.getAmount()).isEqualByComparingTo("29.97");
        }

        @Test
        @DisplayName("重复的退款请求 -> 必须被忽略，不能退两次钱")
        void duplicateRefundIsIgnored() {
            when(refundRepository.findByIdempotencyKey(anyString()))
                    .thenReturn(Optional.of(Refund.builder().id(1L).build()));

            paymentService.processRefund(refundRequest());

            verify(refundRepository, never()).save(any());
            verify(paymentRepository, never()).save(any());
        }

        /**
         * 【补偿必须能处理"要补偿的事根本没发生过"】
         *
         * 支付本来就失败了，但编排器统一发了补偿指令。
         * 抛异常会导致 Kafka 重试 -> 死信队列 -> 误报警。
         */
        @Test
        @DisplayName("原支付记录不存在 -> 安全返回，不抛异常")
        void missingPaymentIsNotAnError() {
            when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(paymentRepository.findById(777L)).thenReturn(Optional.empty());

            paymentService.processRefund(refundRequest());   // 不应抛异常

            verify(refundRepository, never()).save(any());
        }

        @Test
        @DisplayName("原支付已失败 -> 不需要退款")
        void failedPaymentNeedsNoRefund() {
            Payment failed = Payment.builder()
                    .id(777L).orderId(ORDER_ID).userId(42L)
                    .amount(new BigDecimal("29.97"))
                    .idempotencyKey(SAGA_ID)
                    .status(Payment.Status.FAILED)
                    .build();
            when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(paymentRepository.findById(777L)).thenReturn(Optional.of(failed));

            paymentService.processRefund(refundRequest());

            verify(refundRepository, never()).save(any());
        }
    }
}
