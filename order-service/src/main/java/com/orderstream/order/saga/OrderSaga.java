package com.orderstream.order.saga;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Saga 实例的持久化状态。
 *
 * 【这张表就是分布式事务的"日志"，它的存在是编排式相对编排式的核心优势】
 *
 *   Choreography（编排/协同）模式下，流程逻辑分散在各个服务的事件监听器里，
 *   没有任何地方能回答"这笔订单现在卡在哪一步"。排障时要去 N 个服务翻日志，
 *   而且流程超过 3 步后几乎没人能说清全貌。
 *
 *   Orchestration（编排）模式下，编排器持有全局状态，并且【持久化】它。
 *   一条 SQL 就能回答：
 *     SELECT state, COUNT(*) FROM order_sagas GROUP BY state;
 *   立刻看出有多少笔卡在支付、多少笔走了补偿。
 *
 * 【为什么必须持久化，不能放内存】
 *   编排器所在的 order-service 随时可能重启（发版、OOM、节点故障）。
 *   状态在内存里的话，重启后所有进行中的 Saga 都变成孤儿 ——
 *   支付回执来了不知道该干什么，订单永久卡在 PENDING。
 *
 *   放在数据库里之后，重启后 Kafka 会重投未提交 offset 的消息，
 *   编排器从表里读出当前状态继续推进。整个系统对进程崩溃是免疫的。
 *   这正是"状态外置"让服务变得可随意重启(cattle, not pets)的原因。
 */
@Entity
@Table(name = "order_sagas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderSaga {

    /**
     * Saga 实例 ID（UUID）。
     *
     * 为什么不直接用 orderId 做主键：
     *   业务上，同一个订单理论上可以发起第二次 Saga
     *   （比如第一次支付超时后，用户选择重新支付）。
     *   那是【业务上不同的两次分布式事务】，应该有各自独立的状态和补偿记录。
     *   用独立的 sagaId 让这个扩展成为可能，而不需要改表结构。
     *
     *   当前实现里 order_id 上有 UNIQUE 约束（一单一 Saga），
     *   但主键的选择保留了未来的灵活性。
     */
    @Id
    @Column(name = "saga_id", length = 36)
    private String sagaId;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaState state;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /**
     * 支付成功后记下支付单号。
     *
     * 它是【退款补偿的必要输入】：退款不是"撤销"，而是一笔方向相反的新交易，
     * 必须引用原交易才能执行。没有它就没法补偿已完成的支付。
     *
     * 这体现了 Saga 状态表的另一个职责：
     * 它不只记录"走到哪一步了"，还要记录【执行补偿所需的全部信息】。
     * 设计 Saga 状态时要反过来问："如果现在要回滚，我需要知道什么？"
     */
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * 乐观锁。
     *
     * Saga 的状态推进天然是串行的（一步的回执到了才推进下一步），
     * 看起来不需要并发控制。但实际存在两个并发源：
     *   1) 兜底扫描器可能在回执到达的同一瞬间判定它超时并强制补偿
     *   2) Kafka rebalance 期间，同一条回执可能被两个消费者实例短暂地同时处理
     *
     * 加上版本号后，这两种冲突会变成一次明确的 OptimisticLockingFailureException
     * 和一次事务回滚，而不是静默的状态错乱。
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
