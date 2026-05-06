package com.orderstream.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Saga 第二步：支付服务 → Saga 编排器 的响应消息
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResultEvent {
    private Long orderId;
    // 支付是否成功
    private boolean success;
    // 失败时的原因描述（成功时为 null）
    private String failureReason;
}
