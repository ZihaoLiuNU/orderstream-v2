package com.orderstream.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Saga 第一步：订单服务 → 支付服务 的命令消息
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequestEvent {
    private Long orderId;
    private Long userId;
    // 需要扣款的总金额
    private BigDecimal totalAmount;
}
