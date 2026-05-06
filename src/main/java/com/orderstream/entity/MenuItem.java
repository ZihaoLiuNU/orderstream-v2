package com.orderstream.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(
    name = "menu_items",
    indexes = {
        // 按餐厅查询菜单是高频操作（菜单搜索流），建立索引将查询从全表扫描优化为索引查找
        @Index(name = "idx_menu_items_restaurant_id", columnList = "restaurant_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;
}
