package com.orderstream.menu.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存预留记录 —— Saga 步骤 1 的本地事务产物。
 *
 * 它的存在让"库存被谁占着、为什么占着、占了多久"变成可查询的事实，
 * 而不是散落在各处的隐式状态。这是编排式 Saga 相比编排式的核心优势：
 * 分布式事务的每一步都有持久化痕迹。
 */
@Entity
@Table(name = "stock_reservations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockReservation {

    /**
     * 直接用 sagaId 做主键，而不是自增 id + 唯一索引。
     *
     * 好处：重复的预留请求会直接撞主键冲突，数据库层面天然幂等。
     * 这比"先查一下有没有、没有再插"更可靠 —— 后者在并发下仍有竞态窗口。
     *
     * 通用原则：能用数据库约束表达的不变量，就不要用应用代码去维护。
     * 约束是并发安全的，应用代码的检查不是。
     */
    @Id
    @Column(name = "saga_id", length = 36)
    private String sagaId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 这里用了级联的 @OneToMany，和 Restaurant 里刻意不用形成对比。
     *
     * 为什么这里可以用：预留明细是预留单的【组成部分】，不能脱离预留单独立存在，
     * 生命周期完全一致。在 DDD 术语里，StockReservation 是聚合根，
     * items 是聚合内的实体 —— 聚合内部用关联是恰当的。
     *
     * 而 Restaurant 和 MenuItem 是两个【独立的聚合】，菜品有自己的生命周期，
     * 跨聚合引用应该只存 ID。这是 DDD 关于聚合边界的核心规则。
     */
    @OneToMany(mappedBy = "sagaId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<StockReservationItem> items = new ArrayList<>();

    public enum Status {
        /** 已预留，等待 Saga 后续步骤 */
        RESERVED,
        /** 已释放（补偿事务执行过了） */
        RELEASED,
        /** Saga 成功完成，预留转为实际消耗 */
        CONFIRMED
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
