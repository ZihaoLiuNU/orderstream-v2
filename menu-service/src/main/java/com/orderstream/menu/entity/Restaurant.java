package com.orderstream.menu.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "restaurants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 软删除标记。
     * 餐厅不能物理删除：历史订单里引用了它的 id，删掉会让订单详情查不到餐厅名。
     * 而且下架的餐厅数据本身有分析价值（为什么下架、下架前的经营数据）。
     * 一般来说，任何被其他数据引用过的实体都应该软删除。
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 注意这里【没有】 @OneToMany List<MenuItem> menuItems。
     *
     * 这是刻意的。JPA 的双向关联很诱人，但代价很大：
     *   - 任何时候序列化 Restaurant 都可能意外触发加载全部菜品（N+1 的温床）
     *   - 双向关联要手工维护两边的一致性，很容易写出内存和数据库不一致的代码
     *   - 它把"餐厅"和"菜品"绑成了一个大聚合，而 DDD 的建议是聚合要尽量小
     *
     * 需要菜品时显式调 menuItemRepository.findByRestaurantId(...)。
     * 多写一行代码，换来完全可预测的查询行为。
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
