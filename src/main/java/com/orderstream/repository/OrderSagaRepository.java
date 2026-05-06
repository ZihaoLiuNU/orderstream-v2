package com.orderstream.repository;

import com.orderstream.saga.OrderSaga;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, Long> {
    // 通过订单 ID 查找对应的 Saga 记录
    Optional<OrderSaga> findByOrderId(Long orderId);
}
