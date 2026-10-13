package com.orderstream.order.saga;

import com.orderstream.common.Topics;
import com.orderstream.common.event.*;
import com.orderstream.messaging.outbox.OutboxPublisher;
import com.orderstream.order.entity.Order;
import com.orderstream.order.repository.OrderRepository;
import com.orderstream.order.repository.OrderSagaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Saga 编排器测试。
 *
 * ============================================================================
 * 【这组测试验证的是"分布式事务的正确性"，是整个项目最核心的业务保证】
 * ============================================================================
 *
 * 用 Mockito 把 repository 和 outbox 都 mock 掉，
 * 于是【不需要数据库、不需要 Kafka】就能验证完整的 Saga 流程逻辑。
 *
 * 这种测试的价值在于它能覆盖【真实环境下极难构造的场景】：
 *   - 支付失败后的补偿路径（真实环境要等 10% 的概率触发）
 *   - 迟到的乱序消息（真实环境几乎无法人为制造）
 *   - Saga 已完成后又收到旧回执
 *
 * 这些正是 Saga 最容易出 bug 的地方，也正是集成测试最难覆盖的地方。
 *
 * 【单元测试和集成测试的分工】：
 *   单元测试验证【逻辑分支】的正确性（便宜、快、能穷举异常路径）
 *   集成测试验证【组件间真的能连通】（贵、慢、只跑主要路径）
 * 两者不可互相替代。只有集成测试的项目，异常路径基本是没测过的。
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaOrchestratorTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderSagaRepository sagaRepository;
    @Mock private OutboxPublisher outboxPublisher;

    @InjectMocks private OrderSagaOrchestrator orchestrator;

    private static final String SAGA_ID = "saga-123";
    private static final Long ORDER_ID = 1L;

    private Order order;
    private OrderSaga saga;

    @BeforeEach
    void setUp() {
        order = Order.builder()
                .id(ORDER_ID)
                .userId(42L)
                .restaurantId(7L)
                .status(Order.OrderStatus.PENDING)
                .totalAmount(new BigDecimal("29.97"))
                .build();

        saga = OrderSaga.builder()
                .sagaId(SAGA_ID)
                .orderId(ORDER_ID)
                .state(SagaState.STOCK_PENDING)
                .totalAmount(new BigDecimal("29.97"))
                .build();
    }

    private void mockLoads() {
        when(sagaRepository.findById(SAGA_ID)).thenReturn(Optional.of(saga));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    // ======================================================================
    @Nested
    @DisplayName("步骤 1：库存预留回执")
    class StockReservationResult {

        @Test
        @DisplayName("预留成功 -> 推进到支付阶段，并发出支付请求")
        void successAdvancesToPayment() {
            mockLoads();

            orchestrator.onStockReservationResult(stockResult(true, null));

            // Saga 状态推进
            assertThat(saga.getState()).isEqualTo(SagaState.PAYMENT_PENDING);
            // 订单仍然是 PENDING —— 只有支付成功才会变成 CONFIRMED
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PENDING);

            // 发出了支付请求
            ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.PAYMENT_REQUESTED), eq("1"), captor.capture());

            PaymentRequestedEvent published = (PaymentRequestedEvent) captor.getValue();
            assertThat(published.getAmount()).isEqualByComparingTo("29.97");
            // 【关键断言】：幂等键必须是 sagaId。
            // payment-service 靠它建唯一索引防止重复扣款，
            // 传错了整个防重复扣款机制就失效了
            assertThat(published.getIdempotencyKey()).isEqualTo(SAGA_ID);
        }

        @Test
        @DisplayName("预留失败 -> 直接结束 Saga 并取消订单，且不发释放指令")
        void failureCancelsOrderWithoutRelease() {
            mockLoads();

            orchestrator.onStockReservationResult(stockResult(false, "菜品「Burger」库存不足"));

            assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATED);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
            // 取消原因要一路传给用户，不能只说"已取消"
            assertThat(order.getCancelReason()).contains("库存不足");

            // 【关键断言】：不能发库存释放指令。
            // menu-service 在预留失败时已经把自己扣的那部分回滚了，
            // 再发一次释放会导致【库存被多加一份】—— 凭空变出商品。
            verify(outboxPublisher, never()).publish(eq(Topics.STOCK_RELEASE_REQUESTED), any(), any());
            // 但要通知用户订单被取消了
            verify(outboxPublisher).publish(eq(Topics.ORDER_STATUS_CHANGED), eq("1"), any());
        }

        /**
         * 【这个测试守护的是"迟到消息"这个极难在真实环境复现的场景】
         *
         * 场景：Saga 因为超时已被兜底扫描器补偿掉（COMPENSATED），
         *      15 分钟后一条延迟的"预留成功"回执才姗姗来迟。
         *
         * 如果不检查状态就往下推进，订单会从 CANCELLED 被拉回流程，
         * 然后发起一笔【用户根本不知道的支付】—— 彻底的数据损坏。
         *
         * eventId 去重挡不住这个：它是一条【全新的、从未处理过】的消息，
         * 只是来得太晚了。必须靠状态守卫。
         */
        @Test
        @DisplayName("Saga 已结束后收到迟到的回执 -> 必须忽略")
        void ignoresLateResultAfterSagaCompleted() {
            saga.setState(SagaState.COMPENSATED);
            when(sagaRepository.findById(SAGA_ID)).thenReturn(Optional.of(saga));
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

            orchestrator.onStockReservationResult(stockResult(true, null));

            // 状态不变，且没有发出任何新指令
            assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATED);
            verifyNoInteractions(outboxPublisher);
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("步骤 2：支付回执")
    class PaymentResult {

        @BeforeEach
        void advanceToPaymentStage() {
            saga.setState(SagaState.PAYMENT_PENDING);
        }

        @Test
        @DisplayName("支付成功 -> Saga 完成，订单确认，记下 paymentId")
        void successCompletesSaga() {
            mockLoads();

            orchestrator.onPaymentResult(paymentResult(true, 555L, null));

            assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);

            // 【关键断言】：必须记下 paymentId。
            // 它是退款补偿的唯一输入 —— 没有它，将来用户取消订单时
            // 就无法定位那笔交易，钱退不了
            assertThat(saga.getPaymentId()).isEqualTo(555L);

            verify(outboxPublisher).publish(eq(Topics.ORDER_STATUS_CHANGED), eq("1"), any());
            // 成功路径不能触发任何补偿
            verify(outboxPublisher, never()).publish(eq(Topics.STOCK_RELEASE_REQUESTED), any(), any());
        }

        @Test
        @DisplayName("支付失败 -> 释放库存补偿 + 取消订单")
        void failureTriggersStockRelease() {
            mockLoads();

            orchestrator.onPaymentResult(paymentResult(false, null, "余额不足"));

            assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATED);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
            assertThat(order.getCancelReason()).contains("余额不足");

            // 【关键断言】：库存是在步骤 1 真实扣掉的，必须释放，
            // 否则这批库存永久泄漏 —— 系统显示售罄但实际没卖出去，
            // 而且没有任何报错，是最难发现的一类 bug
            verify(outboxPublisher).publish(eq(Topics.STOCK_RELEASE_REQUESTED), eq("1"), any());
            verify(outboxPublisher).publish(eq(Topics.ORDER_STATUS_CHANGED), eq("1"), any());
        }

        @Test
        @DisplayName("Saga 已完成后收到重复的支付回执 -> 必须忽略")
        void ignoresDuplicateAfterCompleted() {
            saga.setState(SagaState.COMPLETED);
            order.transitionTo(Order.OrderStatus.CONFIRMED, null);
            when(sagaRepository.findById(SAGA_ID)).thenReturn(Optional.of(saga));
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

            orchestrator.onPaymentResult(paymentResult(true, 555L, null));

            assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
            verifyNoInteractions(outboxPublisher);
        }

        /**
         * 【这个测试对应原版代码里一个真实的数据损坏 bug】
         *
         * 原版的 handlePaymentResult 没有任何状态检查，
         * 无条件执行 order.setStatus(CONFIRMED)。
         *
         * 时间线：
         *   支付成功 -> CONFIRMED
         *   -> 管理员推进到 PREPARING（餐厅开始做菜）
         *   -> 旧的 PaymentResultEvent 被 Kafka 重投
         *   -> 无条件 setStatus(CONFIRMED)
         *   -> 【订单从 PREPARING 倒退回 CONFIRMED】
         *
         * 餐厅后台的"待处理订单"列表里，这单会重新冒出来，
         * 厨房可能会做第二份。
         */
        @Test
        @DisplayName("订单已被管理员推进后，重投的支付回执不能让状态倒退")
        void lateEventCannotRegressOrderStatus() {
            saga.setState(SagaState.COMPLETED);
            order.transitionTo(Order.OrderStatus.CONFIRMED, null);
            order.transitionTo(Order.OrderStatus.PREPARING, null);

            when(sagaRepository.findById(SAGA_ID)).thenReturn(Optional.of(saga));
            when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

            orchestrator.onPaymentResult(paymentResult(true, 555L, null));

            // 状态守卫拦住了它，订单保持在 PREPARING
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PREPARING);
        }

        /**
         * 【超时补偿 vs 迟到的支付成功 —— 最危险的竞态】
         *
         * 时间线：
         *   支付请求发出 -> payment-service 慢了（或 Kafka 积压）
         *   -> 5 分钟后兜底扫描器判定超时，补偿 + 取消订单
         *   -> 支付其实成功了，回执这时才到
         *
         * 如果像其他迟到消息一样忽略它：钱扣了、订单没了、没有退款。
         * 正确做法是补发退款，且订单保持 CANCELLED 不被拉回。
         */
        @Test
        @DisplayName("Saga 已补偿后收到迟到的支付成功 -> 发起退款，订单保持取消")
        void lateSuccessAfterCompensationTriggersRefund() {
            saga.setState(SagaState.COMPENSATED);
            order.transitionTo(Order.OrderStatus.CANCELLED, "系统超时");
            mockLoads();

            orchestrator.onPaymentResult(paymentResult(true, 555L, null));

            ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.PAYMENT_REFUND_REQUESTED), eq("1"), captor.capture());
            PaymentRefundRequestedEvent refund = (PaymentRefundRequestedEvent) captor.getValue();
            // payment-service 按 sagaId 做退款幂等，按 paymentId 找到那笔钱，两个都不能错
            assertThat(refund.getSagaId()).isEqualTo(SAGA_ID);
            assertThat(refund.getPaymentId()).isEqualTo(555L);

            assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATED);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
            // 订单状态没变，不该再广播状态变更
            verify(outboxPublisher, never()).publish(eq(Topics.ORDER_STATUS_CHANGED), any(), any());
        }

        @Test
        @DisplayName("Saga 已补偿后收到迟到的支付失败 -> 忽略，没钱可退")
        void lateFailureAfterCompensationIsIgnored() {
            saga.setState(SagaState.COMPENSATED);
            order.transitionTo(Order.OrderStatus.CANCELLED, "系统超时");
            mockLoads();

            orchestrator.onPaymentResult(paymentResult(false, null, "余额不足"));

            verifyNoInteractions(outboxPublisher);
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("主动取消的多步补偿")
    class CompensateAndCancel {

        @Test
        @DisplayName("取消已支付订单 -> 同时发起退款和库存释放")
        void cancelPaidOrderRefundsAndReleases() {
            saga.setState(SagaState.COMPLETED);
            saga.setPaymentId(555L);
            order.transitionTo(Order.OrderStatus.CONFIRMED, null);
            when(sagaRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(saga));

            orchestrator.compensateAndCancel(order, "用户主动取消");

            // 钱收了要退
            verify(outboxPublisher).publish(eq(Topics.PAYMENT_REFUND_REQUESTED), eq("1"), any());
            // 库存扣了要还
            verify(outboxPublisher).publish(eq(Topics.STOCK_RELEASE_REQUESTED), eq("1"), any());

            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
            assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATED);
        }

        /**
         * 支付还没成功（Saga 停在 STOCK_PENDING），此时取消不该发退款 ——
         * 根本没扣过钱，发退款指令会让 payment-service 去处理一笔
         * 不存在的交易，制造无谓的告警和排查成本。
         */
        @Test
        @DisplayName("取消未支付订单 -> 只释放库存，不发退款")
        void cancelUnpaidOrderOnlyReleasesStock() {
            saga.setState(SagaState.STOCK_PENDING);
            when(sagaRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(saga));

            orchestrator.compensateAndCancel(order, "用户主动取消");

            verify(outboxPublisher, never()).publish(eq(Topics.PAYMENT_REFUND_REQUESTED), any(), any());
            verify(outboxPublisher).publish(eq(Topics.STOCK_RELEASE_REQUESTED), eq("1"), any());
        }
    }

    // ======================================================================
    // 测试数据构造

    private StockReservationResultEvent stockResult(boolean success, String reason) {
        return StockReservationResultEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .orderId(ORDER_ID)
                .sagaId(SAGA_ID)
                .success(success)
                .failureReason(reason)
                .build();
    }

    private PaymentResultEvent paymentResult(boolean success, Long paymentId, String reason) {
        return PaymentResultEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .orderId(ORDER_ID)
                .sagaId(SAGA_ID)
                .paymentId(paymentId)
                .success(success)
                .failureReason(reason)
                .build();
    }
}
