package com.orderstream.saga;

// Orchestration-based Saga 的状态机
public enum SagaState {
    // 已发起支付请求，等待支付服务响应
    PAYMENT_PENDING,
    // 支付成功，Saga 全部步骤完成
    COMPLETED,
    // 支付失败，已执行补偿事务（订单已取消）
    COMPENSATED
}
