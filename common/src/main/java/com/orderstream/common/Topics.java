package com.orderstream.common;

/**
 * Kafka topic 名称的唯一来源。
 *
 * 命名约定：<聚合根>.<发生的事>（过去式）
 *   事件名用过去式是有意的 —— 事件描述【已经发生的事实】，不可协商、不可拒绝。
 *   如果一个 topic 叫 "cancel-order"，那是命令(Command)不是事件(Event)：命令有明确的接收者、
 *   可以被拒绝；事件是广播的，发布者不关心谁在听。混淆这两者是事件驱动架构最常见的设计错误。
 *
 * 这里 order-service 发给 payment/menu 的其实是【命令】语义（有明确接收者、期待回复），
 * 这是编排式 Saga 的固有特征：编排器指挥各方做事。我保留 requested 后缀来体现这一点。
 */
public final class Topics {

    private Topics() {}

    // ---- Saga 步骤 1：库存预留（order-service → menu-service） ----
    public static final String STOCK_RESERVATION_REQUESTED = "stock.reservation.requested";
    public static final String STOCK_RESERVATION_RESULT    = "stock.reservation.result";
    /** 补偿：释放已预留的库存 */
    public static final String STOCK_RELEASE_REQUESTED     = "stock.release.requested";

    // ---- Saga 步骤 2：支付（order-service → payment-service） ----
    public static final String PAYMENT_REQUESTED           = "payment.requested";
    public static final String PAYMENT_RESULT              = "payment.result";
    /** 补偿：退款 */
    public static final String PAYMENT_REFUND_REQUESTED    = "payment.refund.requested";

    // ---- 领域事件广播（order-service → notification-service / 未来的 analytics） ----
    public static final String ORDER_STATUS_CHANGED        = "order.status.changed";

    // ---- 死信 topic 后缀 ----
    /** 消费重试耗尽后，消息进入 <原topic>.DLT，不阻塞后续消息也不静默丢失 */
    public static final String DLT_SUFFIX = ".DLT";
}
