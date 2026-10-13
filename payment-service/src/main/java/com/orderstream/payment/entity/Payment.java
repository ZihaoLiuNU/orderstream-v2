package com.orderstream.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /**
     * 幂等键，数据库上有唯一索引。
     *
     * 值来自 Saga 的 sagaId（见 OrderSagaOrchestrator 里的说明）：
     *   用 sagaId 而不是 orderId，是因为同一订单理论上可以发起第二次支付
     *   （首次超时后用户重试），那是业务上不同的两笔交易，应该被允许。
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * 外部支付网关的交易号。
     *
     * 真实环境里这是对账和退款的唯一依据 —— 我方的 payment.id 对网关没有意义，
     * 必须用它返回的交易号才能定位那笔钱。
     * 模拟实现里生成一个假的，但字段必须留着：
     * 这类"接入真实系统时才需要，但结构上必须预留"的字段，
     * 最好一开始就设计进去，否则后期加字段要改表、改代码、迁移历史数据。
     */
    @Column(name = "external_txn_id", length = 100)
    private String externalTxnId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum Status {
        SUCCEEDED,
        FAILED,
        /** 已全额退款。注意这只是个便于查询的标记，真正的退款事实记在 refunds 表 */
        REFUNDED
    }

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
