package com.orderstream.saga;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Saga 状态记录表：每条订单对应一条 Saga 记录，追踪分布式事务的完整生命周期
@Entity
@Table(name = "order_sagas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderSaga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 关联的订单 ID（唯一）
    @Column(unique = true, nullable = false)
    private Long orderId;

    // 当前 Saga 所处阶段
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaState state;

    // 支付金额，补偿时用于退款计算
    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // 最后一次状态变更时间，用于超时监控
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
