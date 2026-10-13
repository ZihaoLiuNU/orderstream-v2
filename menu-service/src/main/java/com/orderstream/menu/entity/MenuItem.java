package com.orderstream.menu.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "menu_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 只存外键 ID，不存 @ManyToOne Restaurant 对象。
     *
     * 好处：
     *   - 永远不会有意外的懒加载（LazyInitializationException 的根源）
     *   - 查询菜品时不需要 JOIN 餐厅表
     *   - 实体更接近"数据"而不是"对象图"，行为可预测
     * 代价：需要餐厅信息时要自己查一次。这是值得的交换。
     */
    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 必须是 BigDecimal，绝不能是 double/float。
     *
     * double 是二进制浮点数，0.1 在二进制下是无限循环小数，存进去就已经失真了。
     * 经典演示：System.out.println(0.1 + 0.2) 输出 0.30000000000000004。
     * 在金额上这意味着对账永远差几分钱，且误差会随运算次数累积。
     *
     * BigDecimal 用十进制精确表示。注意两个使用要点：
     *   1) 构造时必须用 new BigDecimal("9.99") 字符串形式，
     *      用 new BigDecimal(9.99) 传 double 会把浮点误差原样带进来
     *   2) 比较用 compareTo() 不用 equals()：
     *      equals 会比较 scale，2.0 和 2.00 判定为不相等
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean available = true;

    /**
     * ========================================================================
     * 【JPA 乐观锁 —— 修复原版代码的 lost update 问题】
     * ========================================================================
     *
     * @Version 让 Hibernate 在每次 UPDATE 时自动加上版本条件：
     *     UPDATE menu_items SET ..., version = version + 1
     *     WHERE id = ? AND version = ?
     *
     * 如果 WHERE 匹配 0 行，说明这条记录在我读出来之后被别人改过了，
     * Hibernate 抛 OptimisticLockingFailureException。
     *
     * 它解决的问题（lost update / 丢失更新）：
     *     线程A 读到 stock=10 ──┐
     *     线程B 读到 stock=10 ──┤   两边都以为自己是从 10 开始的
     *     线程A 写 stock=9    ──┤
     *     线程B 写 stock=9    ──┘   卖了两份，库存只扣了一份！
     *
     * Postgres 默认的 READ COMMITTED 隔离级别【不会】阻止这种情况 ——
     * 这是个非常普遍的误解。READ COMMITTED 只保证读不到未提交的数据，
     * 完全不管"读-改-写"这个序列的原子性。
     *
     * 【为什么叫"乐观"】
     *   它不加任何数据库锁，只是"乐观地假设不会冲突"，冲突了再报错让调用方重试。
     *   对比悲观锁(SELECT ... FOR UPDATE)：悲观锁在读的时候就锁住行，
     *   其他事务必须等待。冲突少时乐观锁性能远好于悲观锁；
     *   冲突多时乐观锁会大量重试，反而不如悲观锁。
     *
     * 【但是 —— 扣库存这个场景我没有用乐观锁】
     *   见 MenuItemRepository.tryDeductStock()：
     *   那里用的是【单条原子条件 UPDATE】，比乐观锁更适合这个场景。
     *   乐观锁在这里保护的是管理员改价、改名这类低频的读-改-写操作。
     *   两种机制各管各的，不是二选一。
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
