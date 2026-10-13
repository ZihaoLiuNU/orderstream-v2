package com.orderstream.common.event;

import lombok.*;
import java.time.Instant;

/**
 * 补偿事务 1：释放已预留的库存。
 *
 * 补偿事务的三个硬性要求：
 *   1. 必须幂等 —— 补偿消息同样是 at-least-once，重复执行不能把库存多加一份。
 *      这里靠 sagaId 去查预留记录的状态实现：已释放的直接返回。
 *   2. 必须【几乎不会失败】—— 补偿失败了没有「补偿的补偿」，只能重试到成功或人工介入。
 *      所以补偿动作要设计得尽可能简单：释放库存只是把一行记录标记为 RELEASED。
 *   3. 是【语义补偿】不是【回滚】—— 数据库回滚能让世界像没发生过，补偿不能。
 *      库存曾经被占用过这个事实留在审计日志里，这是 Saga 放弃隔离性的必然结果。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class StockReleaseRequestedEvent implements DomainEvent {
    private String eventId;
    private Instant occurredAt;

    private Long orderId;
    private String sagaId;
}
