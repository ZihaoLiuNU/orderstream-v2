package com.orderstream.dto;

import lombok.Data;
import java.util.List;

@Data
public class CreateOrderRequest {
    private Long restaurantId;
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        private Long menuItemId;
        private Integer quantity;
    }
}
