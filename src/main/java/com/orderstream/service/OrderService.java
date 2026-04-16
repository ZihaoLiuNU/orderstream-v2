package com.orderstream.service;

import com.orderstream.dto.CreateOrderRequest;
import com.orderstream.dto.OrderResponse;
import com.orderstream.entity.*;
import com.orderstream.exception.ResourceNotFoundException;
import com.orderstream.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createOrder(CreateOrderRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        // 1. 验证用户存在
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        // 2. 验证餐厅存在
        Restaurant restaurant = restaurantRepository.findById(request.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + request.getRestaurantId()));

        // 3. 构建订单（还未保存）
        Order order = Order.builder()
                .user(user)
                .restaurant(restaurant)
                .status(Order.OrderStatus.PENDING)
                .build();

        // 4. 验证所有 menuItem 存在，构建 OrderItem 列表
        // 如果任何一个 menuItemId 不存在，整个事务回滚，Order 也不会保存
        List<OrderItem> items = request.getItems().stream().map(itemReq -> {
            MenuItem menuItem = menuItemRepository.findById(itemReq.getMenuItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + itemReq.getMenuItemId()));
            return OrderItem.builder()
                    .order(order)
                    .menuItem(menuItem)
                    .quantity(itemReq.getQuantity())
                    .price(menuItem.getPrice())
                    .build();
        }).collect(Collectors.toList());

        // 5. 所有验证通过，一次性保存 Order + OrderItems
        order.setOrderItems(items);
        Order saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        return OrderResponse.from(order);
    }
}
