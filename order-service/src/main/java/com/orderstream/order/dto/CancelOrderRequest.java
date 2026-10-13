package com.orderstream.order.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelOrderRequest {
    /** 取消原因，可选。会记进订单并通过通知告诉用户 */
    @Size(max = 500)
    private String reason;
}
