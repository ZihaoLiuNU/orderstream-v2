package com.orderstream.common.event;

import lombok.*;
import java.time.Instant;

/**
 * 补偿事务 2：退款。
 *
 * 什么时候会触发：支付已经成功，但 Saga 的后续步骤失败了。
 * 在我的流程里这是罕见路径（支付是最后一个可能失败的步骤），但必须实现 ——
 * Saga 的正确性恰恰体现在这些罕见路径上，正常路径谁都能写对。
 *
 * 注意退款【不是】把支付记录删掉。它是一笔新的、方向相反的交易，
 * 原支付记录必须保留。这是财务系统的基本要求：账目只能追加，不能修改历史。
 * 这个原则叫 append-only ledger。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentRefundRequestedEvent implements DomainEvent {
    private String eventId;
    private Instant occurredAt;

    private Long orderId;
    private String sagaId;
    private Long paymentId;
    private String reason;
}
