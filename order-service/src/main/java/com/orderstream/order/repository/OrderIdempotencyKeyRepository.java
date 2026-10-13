package com.orderstream.order.repository;

import com.orderstream.order.entity.OrderIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OrderIdempotencyKeyRepository
        extends JpaRepository<OrderIdempotencyKey, OrderIdempotencyKey.Key> {

    /** 按 (key, userId) 精确查找，走复合主键索引 */
    Optional<OrderIdempotencyKey> findByIdempotencyKeyAndUserId(String idempotencyKey, Long userId);

    /**
     * 清理过期的幂等键。
     *
     * 【保留期怎么定】：必须大于客户端可能的最长重试窗口。
     *   移动端 App 通常在几分钟内重试完，24 小时是很充裕的余量。
     *   保留期太短的后果是：一个延迟很久的重试进来时，
     *   key 已经被清理了 -> 被当成新请求 -> 产生重复订单。
     *   幂等机制就在那一刻失效了，而且完全没有报错。
     */
    @Modifying
    @Query("DELETE FROM OrderIdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
