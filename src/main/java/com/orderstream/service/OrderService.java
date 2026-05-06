package com.orderstream.service;

import com.orderstream.dto.CreateOrderRequest;
import com.orderstream.dto.OrderResponse;
import com.orderstream.dto.UpdateOrderStatusRequest;
import com.orderstream.entity.*;
import com.orderstream.event.OrderStatusChangedEvent;
import com.orderstream.exception.BusinessException;
import com.orderstream.exception.ResourceNotFoundException;
import com.orderstream.kafka.OrderEventProducer;
import com.orderstream.repository.*;
import com.orderstream.saga.OrderSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final OrderEventProducer orderEventProducer;
    private final OrderSagaOrchestrator sagaOrchestrator;

    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createOrder(CreateOrderRequest request) {
        // 从 JWT 上下文中获取当前登录用户
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        Restaurant restaurant = restaurantRepository.findById(request.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + request.getRestaurantId()));

        Order order = Order.builder()
                .user(user)
                .restaurant(restaurant)
                .status(Order.OrderStatus.PENDING)
                .build();

        // 构建订单明细，锁定当前菜品价格（防止菜品改价影响已下订单）
        List<OrderItem> items = request.getItems().stream().map(itemReq -> {
            MenuItem menuItem = menuItemRepository.findById(itemReq.getMenuItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + itemReq.getMenuItemId()));
            return OrderItem.builder()
                    .order(order)
                    .menuItem(menuItem)
                    .quantity(itemReq.getQuantity())
                    .price(menuItem.getPrice()) // 快照当前价格
                    .build();
        }).collect(Collectors.toList());

        order.setOrderItems(items);
        Order saved = orderRepository.save(order);

        // 计算订单总金额，启动 Saga 分布式事务（发起支付流程）
        BigDecimal totalAmount = items.stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sagaOrchestrator.startSaga(saved, totalAmount);

        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        return OrderResponse.from(order);
    }

    // 查询当前登录用户的全部历史订单
    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
        return orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    // 管理员手动更新订单状态（正常流转：CONFIRMED→PREPARING→DELIVERED）
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, UpdateOrderStatusRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        Order.OrderStatus previous = order.getStatus();
        Order.OrderStatus next = request.getStatus();

        validateTransition(previous, next);

        order.setStatus(next);
        Order saved = orderRepository.save(order);

        // DB 写成功后发 Kafka 事件（best-effort，不影响主流程）
        orderEventProducer.sendOrderStatusChanged(new OrderStatusChangedEvent(
                saved.getId(),
                saved.getUser().getId(),
                previous,
                next,
                LocalDateTime.now()
        ));

        return OrderResponse.from(saved);
    }

    // 合法的状态流转：PENDING→CONFIRMED→PREPARING→DELIVERED，任意状态可取消
    private void validateTransition(Order.OrderStatus from, Order.OrderStatus to) {
        if (to == Order.OrderStatus.CANCELLED) return;
        boolean valid = switch (from) {
            case PENDING -> to == Order.OrderStatus.CONFIRMED;
            case CONFIRMED -> to == Order.OrderStatus.PREPARING;
            case PREPARING -> to == Order.OrderStatus.DELIVERED;
            default -> false;
        };
        if (!valid) {
            throw new BusinessException("非法状态流转: " + from + " → " + to);
        }
    }
}
