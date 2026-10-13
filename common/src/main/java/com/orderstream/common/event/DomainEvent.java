package com.orderstream.common.event;

import java.time.Instant;

/**
 * 所有跨服务事件的公共契约。
 *
 * eventId 是整个系统幂等性的基石：
 *   Kafka 保证的是 at-least-once —— 消费者处理成功后、提交 offset 前崩溃，重启必然重复消费。
 *   跨系统（Kafka -> Postgres）做不到真正的 exactly-once，因为「写数据库」和「提交 offset」
 *   分属两个不同的事务管理器，无法原子化。
 *   所以正确的做法不是追求 exactly-once 投递，而是让【消费端幂等】，达成 effectively-once：
 *   消费者把处理过的 eventId 记进 processed_events 表（与业务写在同一个本地事务里），
 *   重复消息进来先查表，见过就直接跳过。
 *
 * occurredAt 用 Instant 而不是 LocalDateTime：
 *   LocalDateTime 没有时区信息，跨服务传输时两端的默认时区一旦不同就会静默偏移几小时。
 *   Instant 是 UTC 时间戳，无歧义。这是分布式系统里的经典坑。
 */
public interface DomainEvent {
    String getEventId();
    Instant getOccurredAt();
}
