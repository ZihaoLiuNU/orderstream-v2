package com.orderstream.common.event;

import lombok.*;
import java.math.BigDecimal;

/**
 * 事件里携带的订单行项目。
 *
 * 注意 unitPrice 是【下单那一刻的价格快照】，不是菜品当前价格。
 * 订单一旦创建，价格就固定了 —— 餐厅事后改价不能影响已下的订单。
 * 这在 DDD 里叫「订单是价格的所有者」：价格被复制进订单聚合，而不是每次去 menu 服务查。
 * 这同时也消除了一个跨服务依赖：查历史订单不需要调用 menu-service。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderLineItem {
    private Long menuItemId;
    private String menuItemName;
    private Integer quantity;
    private BigDecimal unitPrice;
}
