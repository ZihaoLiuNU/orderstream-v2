package com.orderstream.order.saga;

import com.orderstream.order.entity.Order;
import com.orderstream.order.repository.OrderRepository;
import com.orderstream.order.repository.OrderSagaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 单个卡死 Saga 的补偿执行器。
 *
 * ============================================================================
 * 【为什么这段代码要单独成一个类，而不是留在 StuckSagaSweeper 里】
 * ============================================================================
 *
 * 又是 Spring AOP 的自调用问题（和 OrderService / OrderPersistenceService 同一个原因）。
 *
 * 如果把这个方法留在 StuckSagaSweeper 里，循环中写成：
 *
 *     for (saga : stuckList) {
 *         try { this.compensateOne(saga.getSagaId()); }   // 自调用！
 *         catch (Exception e) { log.error(...); }
 *     }
 *
 * 那么 @Transactional(REQUIRES_NEW) 【完全不生效】——
 * this.xxx() 不经过代理，事务增强被跳过。
 * 于是所有补偿又回到同一个事务（或者根本没有事务）里，
 * "一条失败只影响一条"这个我们费力设计的隔离性，静默地消失了。
 *
 * 而且这种失效【没有任何报错】：功能看起来是好的，
 * 只有在某一条数据出问题时，才会发现整批都回滚了。
 *
 * 跨 Bean 调用必然经过代理，所以拆开是最可靠的做法。
 *
 * 【这是本项目第二次因为同一个原因拆类】
 *   第一次是 OrderService -> OrderPersistenceService。
 *   一个模式在同一个项目里踩两次，说明它不是偶然 ——
 *   "需要独立事务边界的逻辑，就应该在独立的 Bean 里"
 *   值得作为一条团队规约写进代码规范。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckSagaCompensator {

    private final OrderSagaRepository sagaRepository;
    private final OrderRepository orderRepository;
    private final OrderSagaOrchestrator orchestrator;

    /**
     * 补偿一个卡死的 Saga。独立事务。
     *
     * REQUIRES_NEW 而不是默认的 REQUIRED：
     *   即使将来有人给调用方加了 @Transactional，这里依然会挂起外层、开新事务，
     *   保证"一条失败只回滚一条"。
     *   把隔离性写进传播级别，比依赖"调用方记得不要开事务"可靠得多。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensate(String sagaId, Duration stuckThreshold) {

        OrderSaga saga = sagaRepository.findById(sagaId).orElse(null);

        if (saga == null || !saga.getState().isInFlight()) {
            /*
             * 【在扫描和处理之间，状态可能已经变了】
             *
             * 扫描查出 500 条卡死的 Saga，逐条处理需要几秒。
             * 这期间下游的回执可能刚好到达，把某几条正常推进了。
             *
             * 所以处理前必须【重新读一次并检查状态】，不能相信扫描时的快照。
             * 这是所有"扫描 + 逐条处理"型任务的通用要求：
             *   扫描结果只是【候选集】，不是【确定要处理的集合】。
             * 不重新检查的话，就会把一个正在正常推进的 Saga 强行补偿掉。
             */
            return;
        }

        Order order = orderRepository.findById(saga.getOrderId()).orElse(null);

        if (order == null) {
            // 订单没了但 Saga 还在，数据不一致。标记 FAILED 等人工处理 ——
            // 不要试图自动"修复"，自动修复不一致的数据往往制造更大的不一致
            saga.setState(SagaState.FAILED);
            saga.setFailureReason("订单记录不存在，数据不一致");
            sagaRepository.save(saga);
            log.error("[Saga兜底] 数据不一致：Saga 存在但订单不存在 sagaId={} orderId={}",
                    saga.getSagaId(), saga.getOrderId());
            return;
        }

        if (order.isTerminal()) {
            // 订单已经到终态了，只是 Saga 状态没跟上。直接对齐，不走补偿 ——
            // 对一个已经取消的订单再发一次退款和库存释放是多余且有风险的
            saga.setState(SagaState.COMPENSATED);
            saga.setFailureReason("订单已处于终态 " + order.getStatus() + "，Saga 状态对齐");
            sagaRepository.save(saga);
            return;
        }

        // 复用正常的补偿路径，绝不另写一套。
        // 两套逻辑必然漂移，而兜底路径平时不执行，漂移了也发现不了 ——
        // 等真正需要它的时候才发现它是坏的，那就太晚了
        orchestrator.compensateAndCancel(order,
                "系统超时：Saga 在 " + stuckThreshold + " 内未收到下游回执");

        log.warn("[Saga兜底] 已强制补偿 sagaId={} orderId={}", saga.getSagaId(), saga.getOrderId());
    }
}
