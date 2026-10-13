package com.orderstream.payment.dto;

import com.orderstream.payment.entity.Payment;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentResponse {

    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private String status;
    private String failureReason;
    private Instant createdAt;

    /**
     * 注意这里【没有暴露 idempotencyKey 和 externalTxnId】。
     *
     *   idempotencyKey 就是 sagaId，泄露它等于泄露内部流程标识，
     *   而且知道它的人可以构造出会被幂等命中的请求。
     *   externalTxnId 是支付网关的交易号，属于金融敏感信息。
     *
     * 最小暴露原则：DTO 里的每个字段都要能回答"调用方为什么需要它"。
     * 答不上来就不要放进去。
     *
     * 这也正是不能直接返回 JPA 实体的原因 ——
     * 实体加字段时，它会【自动】出现在所有 API 响应里，
     * 没有任何机制会提醒你刚刚泄露了什么。
     */
    public static PaymentResponse from(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId())
                .orderId(p.getOrderId())
                .amount(p.getAmount())
                .status(p.getStatus().name())
                .failureReason(p.getFailureReason())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
