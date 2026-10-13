package com.orderstream.common.event;

import lombok.*;
import java.time.Instant;

/** Saga 步骤 1 的回执：menu-service 告诉编排器预留成功还是失败。 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class StockReservationResultEvent implements DomainEvent {
    private String eventId;
    private Instant occurredAt;

    private Long orderId;
    private String sagaId;
    private boolean success;
    /** 失败原因，例如「菜品 Burger 库存不足，剩余 2 份」。会一路传给用户，所以要可读。 */
    private String failureReason;
}
