package com.orderstream.order.saga;

import com.orderstream.order.repository.OrderSagaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 卡死 Saga 的兜底处理。
 *
 * ============================================================================
 * 【有了 outbox、幂等、死信队列，为什么还需要这个】
 * ============================================================================
 *
 * 前面那些机制保证的是"消息不丢、不重复执行"。
 * 但它们保证不了一件事：【回执一定会回来】。
 *
 * 回执回不来的真实场景：
 *   - menu-service 的消费代码有 bug，消息进了死信队列，没人处理
 *   - payment-service 挂了一小时，恢复后 Kafka 里的消息已过保留期被清理
 *   - 运维误操作重置了消费者组的 offset
 *   - 某个 topic 被误删重建
 *
 * 这些情况下订单会【永久】停留在 PENDING：
 *   用户看到一个永远在"处理中"的订单，库存被永久占用，
 *   而系统完全没有报错。
 *   这是最糟糕的一类故障：静默的、持续的、不可自愈的。
 *
 * ============================================================================
 * 【设计思想：协调机制 vs 自愈机制】
 * ============================================================================
 *
 *   基于消息的协调（等回执）依赖一个假设：对方还活着并且会来通知我。
 *   基于超时的自愈不依赖任何外部方：我自己就能判断出不对劲并恢复。
 *
 *   健壮的分布式系统两者都要：
 *     协调机制处理 99.9% 的情况 —— 快速、精确
 *     自愈机制处理剩下的 0.1%   —— 慢，但【不依赖任何假设】
 *
 *   TCP 里也是同一个模式：ACK 是协调，超时重传是自愈。
 *   只有 ACK 没有超时的协议，在丢包时会永久挂起。
 */
@Slf4j
@Component
public class StuckSagaSweeper {

    private final OrderSagaRepository sagaRepository;
    private final StuckSagaCompensator compensator;
    private final MeterRegistry meterRegistry;
    private final Duration stuckThreshold;

    /**
     * 每轮处理上限。
     *
     * 【为什么兜底任务必须限流】
     *   故障恢复后可能积压几万条待补偿的 Saga。
     *   一口气全处理完会产生一个巨大的负载尖峰，
     *   把刚刚恢复、还很脆弱的系统【再打垮一次】。
     *
     *   分批、限速、多轮完成，是所有补偿/重试类任务的基本纪律。
     *   500 条/分钟意味着 3 万条要 60 分钟处理完 —— 慢，但系统始终是稳的。
     *   恢复期的目标是"稳步恢复"，不是"立刻恢复"。
     */
    private final int maxPerSweep;

    public StuckSagaSweeper(OrderSagaRepository sagaRepository,
                            StuckSagaCompensator compensator,
                            ObjectProvider<MeterRegistry> meterRegistry,
                            @Value("${orderstream.saga.stuck-threshold:PT5M}") Duration stuckThreshold,
                            @Value("${orderstream.saga.max-per-sweep:500}") int maxPerSweep) {
        this.sagaRepository = sagaRepository;
        this.compensator = compensator;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.stuckThreshold = stuckThreshold;
        this.maxPerSweep = maxPerSweep;
    }

    /**
     * 扫描并强制补偿卡死的 Saga。
     *
     * 【阈值必须远大于正常完成时间】
     *   正常流程：预留(50ms) + 支付(300ms) + 两次 outbox 轮询(1s) < 2 秒。
     *   设成 5 分钟是 150 倍余量。
     *
     *   误杀的代价很高：把一个正在支付的 Saga 强制补偿掉，
     *   支付成功后钱收了但订单已取消，需要人工退款。
     *   所以宁可晚一点发现，也不能误判。
     *
     * 【多层超时的时间必须由内到外递增】
     *   order-service 5 分钟先动手释放库存（正常的补偿路径）；
     *   menu-service 的 10 分钟库存超时是兜底的兜底。
     *   如果搞反了（menu 先动手），order-service 还以为库存占着，
     *   补偿逻辑就会基于错误的前提执行。
     *   任何有多级超时的系统，都要画出时间线确认这个递增关系。
     *
     * ========================================================================
     * 【注意这个方法上【没有】 @Transactional —— 这是刻意的，而且是改过的】
     * ========================================================================
     *
     * 【第一版的两个错误】
     *
     *   错误 1：整个方法一个 @Transactional，循环处理所有卡死的 Saga。
     *     压测后留下 3 万个卡死 Saga，这个事务跑了 3 分半还没结束，
     *     全程占着一条数据库连接和大量行锁。
     *
     *   错误 2：循环里 catch 住异常继续跑。
     *     这在 Spring 事务里是【无效的】：repository 抛出的
     *     RuntimeException 已经让事务被标记为 rollback-only。
     *     后面的循环全是白干，最终在提交时抛 UnexpectedRollbackException，
     *     3 分半的工作【全部回滚】，一条都没补偿成功。
     *     然后 60 秒后又从头再来一遍，永远跑不完。
     *
     *     这是 Spring 事务最容易误解的一点：
     *     【在事务内 catch 异常不等于"当它没发生过"】。
     *     要让某一条失败不影响其他条，必须让每条各自在【独立的事务】里。
     *
     * 【现在的写法】
     *   方法本身无事务，只负责取数据和循环；
     *   每条的补偿由 StuckSagaCompensator（独立 Bean）开一个【独立事务】。
     *   一条失败只回滚它自己，其他条照常提交。
     *
     * 【外加一个限流：每轮最多处理 N 条】
     *   兜底任务永远不该是"一口气把积压全处理完"。
     *   那会在故障恢复时产生一个巨大的负载尖峰，把刚恢复的系统再打垮一次。
     *   分批、限速、多轮完成，是所有补偿/重试类任务的基本纪律。
     */
    @Scheduled(fixedDelayString = "${orderstream.saga.sweep-interval:60000}")
    public void sweepStuckSagas() {
        Instant cutoff = Instant.now().minus(stuckThreshold);

        List<OrderSaga> stuck = sagaRepository.findStuck(
                List.of(SagaState.STOCK_PENDING.name(), SagaState.PAYMENT_PENDING.name()),
                cutoff, maxPerSweep);

        if (stuck.isEmpty()) {
            return;
        }

        // error 级别：走到这里说明主动补偿路径失效了。
        // 兜底机制被触发本身就是一个需要人去查的告警信号，不是正常运转的一部分
        log.error("[Saga兜底] 本轮处理 {} 个超过 {} 未完成的 Saga（每轮上限 {}）",
                stuck.size(), stuckThreshold, maxPerSweep);

        int ok = 0, failed = 0;
        for (OrderSaga saga : stuck) {
            try {
                // 跨 Bean 调用 -> 经过代理 -> REQUIRES_NEW 真正生效。
                // 见 StuckSagaCompensator 类注释里关于自调用陷阱的说明
                compensator.compensate(saga.getSagaId(), stuckThreshold);
                ok++;
            } catch (Exception e) {
                // 现在这个 catch 是【有效】的：compensate 在独立事务里，
                // 它的回滚不会污染本方法（本方法根本没有事务）
                failed++;
                log.error("[Saga兜底] 补偿失败 sagaId={} error={}", saga.getSagaId(), e.getMessage());
            }
        }
        log.warn("[Saga兜底] 本轮完成：成功 {} 失败 {}", ok, failed);
    }

    /**
     * Saga 健康度指标。
     *
     * 这几个数字是整个分布式事务最重要的监控信号：
     *   - 进行中的数量持续上涨 -> 下游变慢或挂了
     *   - COMPENSATED 比例上涨 -> 支付成功率下降或大面积库存不足
     *   - 出现任何 FAILED     -> 必须人工介入，应该直接呼叫值班
     *
     * 【可观测性要和架构一起设计，不是上线后再补】
     *   Saga 的每个状态都对应一个业务含义，暴露成指标之后，
     *   运维不需要懂代码就能判断系统健康程度。
     */
    @Scheduled(fixedDelay = 30_000)
    public void reportSagaMetrics() {
        if (meterRegistry == null) return;

        meterRegistry.gauge("orderstream.saga.stock_pending",
                sagaRepository.countByState(SagaState.STOCK_PENDING));
        meterRegistry.gauge("orderstream.saga.payment_pending",
                sagaRepository.countByState(SagaState.PAYMENT_PENDING));
        meterRegistry.gauge("orderstream.saga.compensated",
                sagaRepository.countByState(SagaState.COMPENSATED));

        long failed = sagaRepository.countByState(SagaState.FAILED);
        meterRegistry.gauge("orderstream.saga.failed", failed);
        if (failed > 0) {
            log.error("[Saga监控] 存在 {} 个 FAILED 状态的 Saga，需要人工对账处理", failed);
        }
    }
}
