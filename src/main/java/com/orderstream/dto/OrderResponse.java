package com.orderstream.dto;

import com.orderstream.entity.Order;
import com.orderstream.entity.OrderItem;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class OrderResponse {
    private Long id;
    private String status;
    private LocalDateTime createdAt;
    private Long restaurantId;
    private String restaurantName;
    private List<OrderItemResponse> items;

    public static OrderResponse from(Order order) {
        OrderResponse r = new OrderResponse();
        r.setId(order.getId());
        r.setStatus(order.getStatus().name());
        r.setCreatedAt(order.getCreatedAt());
        r.setRestaurantId(order.getRestaurant().getId());
        r.setRestaurantName(order.getRestaurant().getName());
        r.setItems(order.getOrderItems().stream()
                .map(OrderItemResponse::from)
                .collect(Collectors.toList()));
        return r;
    }

    @Data
    public static class OrderItemResponse {
        private Long menuItemId;
        private String menuItemName;
        private Integer quantity;
        private BigDecimal price;

        public static OrderItemResponse from(OrderItem item) {
            OrderItemResponse r = new OrderItemResponse();
            r.setMenuItemId(item.getMenuItem().getId());
            r.setMenuItemName(item.getMenuItem().getName());
            r.setQuantity(item.getQuantity());
            r.setPrice(item.getPrice());
            return r;
        }
    }
}
