package com.orderstream.order.saga;

import com.orderstream.common.Topics;
import com.orderstream.common.event.*;
import com.orderstream.common.web.ResourceNotFoundException;
import com.orderstream.messaging.outbox.OutboxPublisher;
import com.orderstream.order.entity.Order;
import com.orderstream.order.repository.OrderRepository;
import com.orderstream.order.repository.OrderSagaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * ============================================================================
 * 编排式 Saga 的编排器（Orchestrator）
 * ============================================================================
 *
 * 【什么是 Saga，以及为什么需要它】
 *
 *   一次下单要改三个服务的数据：扣库存(menu_db)、扣款(payment_db)、建订单(order_db)。
 *   三个数据库，没有任何本地事务能横跨它们。
 *
 *   传统方案是两阶段提交(2PC)：一个协调者让所有参与者先 prepare（持有锁），
 *   全部 prepare 成功后再统一 commit。问题是：
 *     - 参与者从 prepare 到 commit 期间【一直持有锁】，吞吐极低
 *     - 协调者在 prepare 之后崩溃，参与者会无限期持锁（in-doubt transaction），
 *       整条链路阻塞，必须人工介入
 *     - 第三方支付网关（Stripe/支付宝）根本不提供 prepare/commit 接口，
 *       所以 2PC 在真实业务里【根本不可用】
 *
 *   Saga 换了个思路：把分布式事务拆成一串【本地事务】，
 *   每一步立刻提交并释放锁；某一步失败了，就按相反顺序执行前面步骤的
 *   【补偿事务】，把已经做的事在业务语义上撤销掉。
 *
 *   代价是放弃了隔离性(ACID 里的 I)：中间状态对外可见。
 *   订单在 PENDING 时，用户看得到这个"还没最终确定"的订单。
 *   这必须用业务手段处理 —— 我用 PENDING 状态本身作为【语义锁】，
 *   它明确告诉所有读者"这笔还没定"。
 *
 * 【为什么选编排(Orchestration)而不是编舞(Choreography)】
 *
 *   Choreography：没有中心，每个服务监听事件、自己决定下一步做什么。
 *     优点：服务间零耦合，加新服务不用改老服务
 *     缺点：流程逻辑【分散】在 N 个服务里，没有任何地方能回答
 *          "这笔订单现在卡在哪"。超过 3 步之后基本无法维护，
 *          而且很容易出现循环触发。
 *
 *   Orchestration（本实现）：一个中心编排器掌握全局状态机。
 *     优点：流程一目了然（就是这个类），状态持久化在 order_sagas 表里，
 *          一条 SQL 就能统计出各步骤的积压情况。排障成本低一个数量级。
 *     缺点：编排器是单点（但它无状态 + 状态外置，可以多实例）；
 *          它需要知道所有参与者，耦合度更高。
 *
 *   我的选择理由：【可观测性】。
 *   一个人维护的系统，出问题时能快速定位比"架构最解耦"重要得多。
 *   而且订单流程未来一定会加步骤（优惠券核销、骑手调度、积分），
 *   编排式加一步只改这一个类，编舞式要改好几个服务。
 *
 * ============================================================================
 * 【这个类的所有方法都用 Propagation.MANDATORY】
 * ============================================================================
 *   它们全部由 @KafkaListener（已开启事务）或 OrderService（已开启事务）调用。
 *   MANDATORY 强制要求"必须已经在事务里"，否则立刻抛异常。
 *
 *   这不是吹毛求疵：如果某个调用方忘了开事务，
 *   "更新 Saga 状态"和"写 outbox 事件"就会分属两个独立事务，
 *   在中间崩溃就会产生【状态已推进但下一步指令没发出】的永久卡死。
 *   而这种 bug 平时完全看不出来，只在故障时暴露。
 *   用 MANDATORY 让误用在开发阶段就响亮地失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final OrderRepository orderRepository;
    private final OrderSagaRepository sagaRepository;
    private final OutboxPublisher outboxPublisher;

    // ======================================================================
    // 步骤 1 的回执：库存预留结果
    // ======================================================================

    /**
     * 收到 menu-service 的库存预留结果。
     *
     *   成功 -> 推进到步骤 2（发起支付）
     *   失败 -> 直接补偿结束（库存根本没扣成功，不需要释放），订单取消
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStockReservationResult(StockReservationResultEvent event) {

        OrderSaga saga = loadSaga(event.getSagaId());
        Order order = loadOrder(event.getOrderId());

        /*
         * 【状态守卫 —— 拒绝处理"不该在这个状态收到的消息"】
         *
         * 这一层检查防的是【乱序和迟到的消息】，和 eventId 去重防的东西不同：
         *   eventId 去重防的是"同一条消息来了两次"
         *   状态守卫防的是"一条本该在更早阶段处理的消息，现在才到"
         *
         * 具体场景：Saga 已经因为超时被兜底扫描器判定失败并补偿了(COMPENSATED)，
         * 这时候一条延迟了 15 分钟的"预留成功"消息才姗姗来迟。
         * 如果不检查状态就往下推进，订单会从 CANCELLED 被拉回流程 ——
         * 那是彻底的数据损坏。
         *
         * 通用原则：【状态机不仅要校验"能不能转"，还要校验"现在该不该转"】。
         */
        if (saga.getState() != SagaState.STOCK_PENDING) {
            log.warn("[Saga] 忽略状态不匹配的库存回执 sagaId={} 当前状态={} 期望=STOCK_PENDING",
                    saga.getSagaId(), saga.getState());
            return;
        }

        if (!event.isSuccess()) {
            // 预留失败：库存服务已经把自己扣的那部分回滚了（见 StockService.reserve），
            // 所以这里【不需要】发释放指令。直接结束 Saga
            saga.setState(SagaState.COMPENSATED);
            saga.setFailureReason(event.getFailureReason());
            sagaRepository.save(saga);

            cancelOrderWithReason(order, event.getFailureReason());

            log.info("[Saga-1] 库存预留失败，订单已取消 orderId={} 原因={}",
                    order.getId(), event.getFailureReason());
            return;
        }

        // ------------------------------------------------------------------
        // 预留成功，推进到步骤 2：发起支付
        // ------------------------------------------------------------------
        saga.setState(SagaState.PAYMENT_PENDING);
        sagaRepository.save(saga);

        outboxPublisher.publish(
                Topics.PAYMENT_REQUESTED,
                String.valueOf(order.getId()),
                PaymentRequestedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(order.getId())
                        .sagaId(saga.getSagaId())
                        .userId(order.getUserId())
                        .amount(saga.getTotalAmount())
                        /*
                         * 【用 sagaId 做支付幂等键】
                         *
                         * payment-service 会在这个键上建唯一索引。
                         * 即使这条消息被重复投递，第二次也只会返回第一次的结果，
                         * 绝不会重复扣款。
                         *
                         * 为什么用 sagaId 而不是 orderId：
                         *   同一订单理论上可以发起第二次 Saga（首次支付超时后用户重试），
                         *   那是业务上不同的两笔支付，应该被允许。
                         *   用 sagaId 粒度更准确。
                         *
                         * 这和 Stripe 的 Idempotency-Key 是同一个机制 ——
                         * 任何有副作用且可能被重试的外部调用，都必须带幂等键。
                         */
                        .idempotencyKey(saga.getSagaId())
                        .build());

        log.info("[Saga-1] 库存预留成功，进入支付阶段 orderId={} sagaId={} 金额={}",
                order.getId(), saga.getSagaId(), saga.getTotalAmount());
    }

    // ======================================================================
    // 步骤 2 的回执：支付结果
    // ======================================================================

    /**
     * 收到 payment-service 的支付结果。
     *
     *   成功 -> Saga 完成，订单 CONFIRMED
     *   失败 -> 执行补偿：释放库存 + 取消订单
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentResult(PaymentResultEvent event) {

        OrderSaga saga = loadSaga(event.getSagaId());
        Order order = loadOrder(event.getOrderId());

        /*
         * 【迟到的"支付成功"—— 不能像其他迟到消息一样直接忽略】
         *
         * 场景：支付回执超过 stuck-threshold 才到，兜底扫描器已经把 Saga 补偿掉、
         * 订单取消了（或者用户在 PAYMENT_PENDING 期间主动取消）。
         * 这时 payment-service 那边其实【已经扣款成功】。
         *
         * 如果和下面的通用状态守卫一样直接 return：
         *   钱扣了、订单取消了、没人退款 —— 这正是整个项目要防的那种错误。
         *
         * 所以这里改成【补发退款】，而不是推进订单（订单已是终态，不能拉回来）。
         * 重复发退款是安全的：payment-service 用 "refund:" + sagaId 做幂等键，
         * 同一个 Saga 最多退一次（见 PaymentService.processRefund）。
         */
        if (saga.getState() == SagaState.COMPENSATED && event.isSuccess()) {
            saga.setPaymentId(event.getPaymentId());
            sagaRepository.save(saga);
            publishRefund(order, saga, "支付回执迟到，Saga 已补偿，自动退款");
            log.warn("[Saga] Saga 已补偿后收到支付成功回执，已发起退款 sagaId={} orderId={} paymentId={}",
                    saga.getSagaId(), order.getId(), event.getPaymentId());
            return;
        }

        if (saga.getState() != SagaState.PAYMENT_PENDING) {
            log.warn("[Saga] 忽略状态不匹配的支付回执 sagaId={} 当前状态={} 期望=PAYMENT_PENDING",
                    saga.getSagaId(), saga.getState());
            return;
        }

        if (event.isSuccess()) {
            // ------------------------------------------------------------------
            // 前向恢复：Saga 成功完成
            // ------------------------------------------------------------------
            saga.setState(SagaState.COMPLETED);
            saga.setPaymentId(event.getPaymentId());   // 留着退款时用
            sagaRepository.save(saga);

            Order.OrderStatus previous = order.getStatus();
            /*
             * 【这一步是 pivot transaction 之后的"必定成功"步骤】
             *
             * 支付已经成功了（钱收了），此时【绝不能失败】。
             * 它之所以安全，是因为它是一次纯本地操作：
             * 改 orders 表的一个字段，和上面改 order_sagas 在同一个事务里。
             * 不涉及任何远程调用，没有独立失败的可能。
             *
             * 这就是 Saga 设计里的关键要求：
             *   pivot（最难补偿的那一步，这里是支付）之后的所有步骤
             *   必须是 retriable 的 —— 可以重试到成功，不需要回滚。
             *   把它们设计成纯本地事务是达成这一点最简单的办法。
             */
            order.transitionTo(Order.OrderStatus.CONFIRMED, null);
            publishStatusChanged(order, previous, "支付成功");

            log.info("[Saga-2] 支付成功，Saga 完成 orderId={} sagaId={} paymentId={}",
                    order.getId(), saga.getSagaId(), event.getPaymentId());

        } else {
            // ------------------------------------------------------------------
            // 后向补偿：支付失败，撤销已预留的库存
            // ------------------------------------------------------------------
            saga.setState(SagaState.COMPENSATED);
            saga.setFailureReason(event.getFailureReason());
            sagaRepository.save(saga);

            /*
             * 【补偿指令是"发出即认为完成"，不等回执 —— 为什么这样是安全的】
             *
             * 我没有为"释放库存"设计一个回执状态(比如 COMPENSATING_STOCK)，
             * 而是发出指令后直接把 Saga 标记为 COMPENSATED。理由：
             *
             *   1) 投递有保证：通过 outbox 发出，和状态变更同一事务，
             *      relay 会一直重试到 Kafka 确认接收。消息不可能丢。
             *   2) 执行有保证：menu-service 的 release 是幂等的，
             *      重复投递不会多退库存。
             *   3) 兜底有保证：menu-service 自己有 StaleReservationSweeper，
             *      即使这条指令因为极端情况没到，超时后也会自动释放。
             *
             * 三层保证都到位之后，再加一个"等待补偿回执"的状态，
             * 只会增加一个新的可能卡住的中间态，而不增加任何安全性。
             *
             * 这是个值得强调的设计判断：
             *   【不要为了形式上的完整而增加状态】。
             *   每个中间状态都是一个可能卡死的地方，都需要超时处理和监控。
             *   只在它确实带来安全性时才引入。
             */
            outboxPublisher.publish(
                    Topics.STOCK_RELEASE_REQUESTED,
                    String.valueOf(order.getId()),
                    StockReleaseRequestedEvent.builder()
                            .eventId(UUID.randomUUID().toString())
                            .occurredAt(Instant.now())
                            .orderId(order.getId())
                            .sagaId(saga.getSagaId())
                            .build());

            cancelOrderWithReason(order, "支付失败: " + event.getFailureReason());

            log.info("[Saga-2] 支付失败，已触发库存释放补偿 orderId={} 原因={}",
                    order.getId(), event.getFailureReason());
        }
    }

    // ======================================================================
    // 用户/管理员主动取消（已确认订单的完整补偿）
    // ======================================================================

    /**
     * 取消一个订单，并根据它当前所处的阶段执行相应的补偿。
     *
     * 【这是补偿逻辑最复杂的地方，因为"该撤销什么"取决于"已经做了什么"】
     *
     *   订单 PENDING（Saga 还在跑）：
     *       库存可能已预留、支付可能已发起。发出释放指令即可。
     *       支付如果正在进行中，它的回执到达时会被状态守卫挡住（Saga 已是 COMPENSATED）。
     *
     *   订单 CONFIRMED / PREPARING（Saga 已完成）：
     *       钱收了、库存扣了。两个都要撤销：退款 + 释放库存。
     *
     * 补偿的顺序是【与执行顺序相反】的，这是 Saga 的标准要求：
     *   执行顺序：扣库存 -> 扣款
     *   补偿顺序：退款 -> 释放库存
     * 虽然这两个补偿互不依赖（可以并行），但保持反序是个好习惯 ——
     * 在有依赖关系的流程里，顺序错了会导致补偿失败。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void compensateAndCancel(Order order, String reason) {

        OrderSaga saga = sagaRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Saga 不存在，orderId=" + order.getId()));

        // 1) 支付已完成 -> 发起退款
        if (saga.getState() == SagaState.COMPLETED && saga.getPaymentId() != null) {
            publishRefund(order, saga, reason);
        }

        // 2) 释放库存。
        //    无条件发出 —— menu-service 找不到预留单时会安全地忽略（见 StockService.release）。
        //    "让补偿方处理'要补偿的事没发生过'这种情况"，比"让发起方精确判断该不该补偿"
        //    要健壮得多：前者只需要一处逻辑，后者需要发起方完整复刻对方的状态
        outboxPublisher.publish(
                Topics.STOCK_RELEASE_REQUESTED,
                String.valueOf(order.getId()),
                StockReleaseRequestedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(order.getId())
                        .sagaId(saga.getSagaId())
                        .build());

        saga.setState(SagaState.COMPENSATED);
        saga.setFailureReason(reason);
        sagaRepository.save(saga);

        cancelOrderWithReason(order, reason);
    }

    // ======================================================================
    // 工具方法
    // ======================================================================

    private void publishRefund(Order order, OrderSaga saga, String reason) {
        outboxPublisher.publish(
                Topics.PAYMENT_REFUND_REQUESTED,
                String.valueOf(order.getId()),
                PaymentRefundRequestedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(order.getId())
                        .sagaId(saga.getSagaId())
                        .paymentId(saga.getPaymentId())
                        .reason(reason)
                        .build());
        log.info("[补偿] 已发起退款 orderId={} paymentId={}", order.getId(), saga.getPaymentId());
    }

    /**
     * 发布订单状态变更事件（广播给 notification-service 等下游）。
     *
     * 这是【事件】不是【命令】：order-service 完全不知道谁在消费它。
     * 将来加一个 analytics-service 做实时大盘，只需要新起一个消费者组，
     * 这里一行代码都不用改。这才是"用 Kafka 解耦下游"的真正含义。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStatusChanged(Order order, Order.OrderStatus previous, String reason) {
        outboxPublisher.publish(
                Topics.ORDER_STATUS_CHANGED,
                String.valueOf(order.getId()),
                OrderStatusChangedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(order.getId())
                        .userId(order.getUserId())
                        .previousStatus(previous.name())
                        .newStatus(order.getStatus().name())
                        .reason(reason)
                        .build());
    }

    private void cancelOrderWithReason(Order order, String reason) {
        Order.OrderStatus previous = order.getStatus();
        order.transitionTo(Order.OrderStatus.CANCELLED, reason);
        orderRepository.save(order);
        publishStatusChanged(order, previous, reason);
    }

    private OrderSaga loadSaga(String sagaId) {
        return sagaRepository.findById(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("Saga 不存在: " + sagaId));
    }

    private Order loadOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("订单不存在: " + orderId));
    }
}
