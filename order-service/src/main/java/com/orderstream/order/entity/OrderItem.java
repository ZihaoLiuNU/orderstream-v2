package com.orderstream.order.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * 订单明细。
 *
 * 【这个类的每个字段都是"快照"，不是"引用"】
 *   menuItemName 和 unitPrice 都是下单那一刻从 menu-service 拿到后复制进来的，
 *   之后永远不再更新。
 *
 * 为什么必须这样：
 *   - 业务上：餐厅明天涨价，不能影响昨天已经成交的订单。
 *     订单是一份【历史合同】，合同一旦签订，条款就固定了。
 *   - 架构上：查历史订单不需要调用 menu-service。
 *     menu-service 宕机时，订单详情依然可以正常打开。
 *     这就是"通过数据冗余换取服务自治"—— 微服务里最常见的一个取舍。
 *   - 数据上：菜品被下架/删除后，历史订单依然能显示出完整信息。
 *
 * 代价是数据冗余，但这里的冗余是【有意义的】：
 * 它存的不是"当前值的副本"，而是"当时值的记录"，两者语义完全不同。
 */
@Entity
@Table(name = "order_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 只存 ID，不存 menu-service 的实体（跨服务，本地根本没有那张表） */
    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @Column(name = "menu_item_name", nullable = false, length = 200)
    private String menuItemName;

    @Column(nullable = false)
    private Integer quantity;

    /** 下单时的单价快照 */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /**
     * 小计。
     *
     * 用 BigDecimal.multiply，绝不能用 double：
     *   9.99 * 3 用 double 算出来是 29.970000000000002。
     *   在订单金额上，这种误差会一路传到支付金额，导致对账差几分钱。
     *
     * 注意 multiply 的结果 scale 是两个操作数 scale 之和（2 + 0 = 2），
     * 所以这里不需要额外 setScale。但在做除法时必须显式指定 scale 和舍入模式，
     * 否则除不尽会直接抛 ArithmeticException。
     */
    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
