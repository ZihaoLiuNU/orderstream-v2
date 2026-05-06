package com.orderstream.event;

import com.orderstream.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusChangedEvent {
    private Long orderId;
    private Long userId;
    private Order.OrderStatus previousStatus;
    private Order.OrderStatus newStatus;
    private LocalDateTime changedAt;
}
