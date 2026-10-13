package com.orderstream.menu.entity;

import jakarta.persistence.*;
import lombok.*;

/** 预留明细：这次预留占用了哪个菜品、几份。释放时按它归还库存。 */
@Entity
@Table(name = "stock_reservation_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false, length = 36)
    private String sagaId;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @Column(nullable = false)
    private Integer quantity;
}
