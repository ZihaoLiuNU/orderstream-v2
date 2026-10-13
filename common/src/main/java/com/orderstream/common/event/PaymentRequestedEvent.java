package com.orderstream.common.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Saga 步骤 2：order-service 请求 payment-service 扣款。
 *
 * idempotencyKey 是这条链路上最重要的字段：
 *   支付是典型的「绝不能重复执行」的操作。如果这条消息被重投，用户会被扣两次款。
 *   payment-service 拿 idempotencyKey 建唯一索引，第二次进来直接返回第一次的结果，
 *   而不是再扣一次。这也是 Stripe / Square 等真实支付网关对外暴露的标准机制
 *   （HTTP 头 Idempotency-Key），不是我自己发明的。
 *
 * 为什么用 sagaId 而不是 orderId 做 idempotencyKey：
 *   同一个订单理论上可能发起第二次支付（比如第一次超时后用户重试），
 *   那是【业务上不同的两次支付】，应该被允许。用 sagaId 粒度更准确。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentRequestedEvent implements DomainEvent {
    private String eventId;
    private Instant occurredAt;

    private Long orderId;
    private String sagaId;
    private Long userId;
    private BigDecimal amount;
    /** 支付去重键，payment-service 上有唯一索引 */
    private String idempotencyKey;
}
