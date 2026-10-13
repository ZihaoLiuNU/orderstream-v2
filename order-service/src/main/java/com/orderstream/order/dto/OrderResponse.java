package com.orderstream.order.dto;

import com.orderstream.order.entity.Order;
import com.orderstream.order.entity.OrderItem;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 订单的对外表示。
 *
 * 【为什么不直接返回 Order 实体】
 *   1) 安全：实体加字段时会自动出现在 API 响应里，没有任何东西提醒你。
 *      给 Order 加一个 internalRiskScore 字段，它就泄露给所有用户了。
 *   2) 稳定：实体是内部模型，会随重构变化；DTO 是对外契约，必须稳定。
 *      直接返回实体等于把内部结构变成了公开 API。
 *   3) 懒加载：实体带 Hibernate 代理，序列化时可能触发意外的懒加载查询，
 *      甚至在事务外抛 LazyInitializationException。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderResponse {

    private Long id;
    private Long restaurantId;
    private String status;
    private BigDecimal totalAmount;
    private String cancelReason;
    private Instant createdAt;
    private List<Item> items;

    /**
     * 注意这里【没有暴露 userId】。
     *
     * 管理员能查所有订单，如果响应里带 userId，
     * 就等于把"用户 ID 到订单"的映射关系批量泄露了。
     * 管理员需要用户信息时应该走单独的、有审计的接口。
     *
     * 最小暴露原则：每个字段都要能回答"调用方为什么需要它"。
     * 答不上来就不要放进 DTO。
     */

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Item {
        private Long menuItemId;
        private String menuItemName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
    }

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .restaurantId(order.getRestaurantId())
                .status(order.getStatus().name())
                .totalAmount(order.getTotalAmount())
                .cancelReason(order.getCancelReason())
                .createdAt(order.getCreatedAt())
                .items(order.getItems().stream().map(OrderResponse::toItem).toList())
                .build();
    }

    private static Item toItem(OrderItem i) {
        return Item.builder()
                .menuItemId(i.getMenuItemId())
                .menuItemName(i.getMenuItemName())
                .quantity(i.getQuantity())
                .unitPrice(i.getUnitPrice())
                .subtotal(i.subtotal())
                .build();
    }
}
