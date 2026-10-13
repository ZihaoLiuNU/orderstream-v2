package com.orderstream.common.event;

import lombok.*;
import java.time.Instant;
import java.util.List;

/**
 * Saga 步骤 1：order-service 请求 menu-service 预留库存。
 *
 * 为什么下单要先扣库存再付款，而不是反过来：
 *   如果先付款后扣库存，库存不足时就必须退款 —— 退款是慢的、可能失败的、还要处理手续费。
 *   先预留库存（本地、快、几乎不会失败），再付款，失败了只需要把预留释放掉，代价极低。
 *   Saga 设计的一条通用原则：把【容易补偿】的步骤放前面，把【难以补偿】的步骤尽量靠后。
 *   最难补偿的那一步叫 pivot transaction（支点），它之后的步骤都应该是必定成功的。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class StockReservationRequestedEvent implements DomainEvent {
    private String eventId;
    private Instant occurredAt;

    private Long orderId;
    /** 幂等/关联用：menu-service 用它标记这批预留属于哪个 Saga */
    private String sagaId;
    private List<OrderLineItem> items;
}
