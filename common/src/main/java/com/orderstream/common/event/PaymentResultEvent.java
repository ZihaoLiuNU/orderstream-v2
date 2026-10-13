package com.orderstream.common.event;

import lombok.*;
import java.time.Instant;

/** Saga 步骤 2 的回执：支付成功还是失败。 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentResultEvent implements DomainEvent {
    private String eventId;
    private Instant occurredAt;

    private Long orderId;
    private String sagaId;
    /** payment-service 侧的支付单号，退款时要用它定位原交易 */
    private Long paymentId;
    private boolean success;
    private String failureReason;
}
