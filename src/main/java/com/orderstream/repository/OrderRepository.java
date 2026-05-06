package com.orderstream.repository;

import com.orderstream.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    // 查询某用户的全部订单，按下单时间倒序（利用 idx_orders_user_id 索引）
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
}
