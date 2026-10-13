package com.orderstream.order.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * 下单幂等键。
 *
 * 【解决的问题：善意的重复请求】
 *   用户点了"提交订单"，网络慢，App 3 秒没收到响应就自动重试。
 *   但服务端其实已经成功创建了订单 —— 重试会创建第二个。
 *   用户看到账户里有两笔一模一样的订单，被扣了两次钱。
 *
 *   这不是攻击，是分布式系统里【必然发生】的正常现象：
 *   客户端永远无法区分"请求没到达"和"响应丢失了"。
 *   既然区分不了，客户端就只能重试；那么服务端就必须能容忍重试。
 *
 * 【为什么不能用"查一下最近有没有相同订单"来判重】
 *   那种启发式判断(比如"5秒内同一用户同样商品视为重复")一定会出错：
 *     - 用户真的想连续点两杯一样的咖啡怎么办？被误判成重复
 *     - 间隔 6 秒的重试怎么办？漏判
 *   幂等键把判断依据从"猜测"变成了【客户端明确声明】：
 *   同一个 key = 同一次意图，不同 key = 不同意图。没有歧义。
 */
@Entity
@Table(name = "order_idempotency_keys")
@IdClass(OrderIdempotencyKey.Key.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderIdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /**
     * 【user_id 必须是主键的一部分】
     *
     * 只用 key 做主键的话，会出现一个隐蔽的漏洞：
     *   攻击者随便构造一个 Idempotency-Key 去下单。
     *   如果这个 key 恰好和别的用户用过的 key 相同，
     *   服务端会认为"这是重复请求"，然后把【别人的 order_id】返回给攻击者。
     *   接下来攻击者就能用这个 ID 去查别人的订单。
     *
     * 加上 user_id 后，每个用户的 key 空间完全隔离，撞车也互不影响。
     *
     * 这是个很好的例子，说明"幂等"这种看似纯技术的机制，
     * 实现时同样要考虑【多租户隔离】—— 任何以客户端输入为键的缓存/去重，
     * 都必须把身份纳入键。
     */
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Key implements java.io.Serializable {
        private String idempotencyKey;
        private Long userId;
    }
}
