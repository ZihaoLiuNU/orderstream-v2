package com.orderstream.payment.repository;

import com.orderstream.payment.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** 幂等查找：走 uk_payments_idempotency 唯一索引，是一次极快的索引查找 */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByOrderId(Long orderId);

    /** 用户查自己的支付记录，走 idx_payments_user_created 复合索引（过滤+排序一次完成） */
    Page<Payment> findByUserId(Long userId, Pageable pageable);
}
