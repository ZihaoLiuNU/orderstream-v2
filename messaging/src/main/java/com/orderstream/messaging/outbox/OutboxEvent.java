package com.orderstream.messaging.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Transactional Outbox 表。
 *
 * ============================================================================
 * 【它解决的问题：dual-write（双写不一致）】
 * ============================================================================
 *
 * 原版代码是这样写的：
 *
 *     @Transactional
 *     public void createOrder(...) {
 *         orderRepository.save(order);              // 写 Postgres，还没 commit
 *         kafkaTemplate.send("payment.requested");  // 写 Kafka，立刻就发出去了
 *     }
 *
 * 这里有两个独立的存储系统，没有任何机制能让这两次写入原子化。三种故障：
 *
 *   1. 消息先到，事务后 commit（甚至还没 commit）：
 *      payment-service 收到消息去查订单 -> 查不到 -> 抛异常 -> 重试耗尽 -> 消息丢弃
 *      -> 订单永久卡在 PENDING。原版靠 PaymentSimulator 里那句
 *      Thread.sleep(100~500ms) 侥幸躲过，那是靠运气，不是靠设计。
 *
 *   2. 事务 rollback，但消息已经发出去了：
 *      订单在数据库里不存在，但用户被扣了款。这是最严重的一种 —— 真金白银的损失。
 *
 *   3. 事务 commit 成功，但 Kafka 发送失败（broker 抖动/网络分区）：
 *      订单创建了，但支付流程永远不会启动。用户付不了款，订单卡死。
 *
 * ============================================================================
 * 【Outbox 的解法：把两次写变成一次写】
 * ============================================================================
 *
 *     @Transactional
 *     public void createOrder(...) {
 *         orderRepository.save(order);        // 写 order 表
 *         outboxPublisher.publish(event);     // 写 outbox 表 —— 同一个数据库、同一个事务！
 *     }                                       // 一起 commit，或者一起 rollback
 *
 *     // 另一个独立的线程/进程轮询 outbox 表，把未发送的记录投递到 Kafka
 *
 * 关键洞察：业务数据和「要发的消息」现在在【同一个数据库】里，
 * 所以本地事务的 ACID 天然覆盖了两者。不需要 2PC，不需要分布式事务协调者。
 *
 * ============================================================================
 * 【代价，必须讲出来】
 * ============================================================================
 *
 *   1. 消息投递有延迟（等于轮询间隔）。可以用 Debezium 这类 CDC 工具直接读
 *      Postgres 的 WAL 日志来消除延迟，代价是引入一个新的重量级组件。
 *
 *   2. 语义是 at-least-once，不是 exactly-once：
 *      relay 有可能「Kafka 已经收到了，但还没来得及把 sent_at 写回去」就崩了，
 *      重启后会重发一次。所以【消费端必须幂等】—— 见 idempotency 包。
 *      Outbox 保证的是「不丢」，幂等消费保证的是「不重」，两者必须配对使用。
 *
 *   3. outbox 表会持续增长，需要清理任务。
 *
 * ============================================================================
 * 【为什么不用 @TransactionalEventListener(AFTER_COMMIT)】
 * ============================================================================
 *
 *   那个方案能解决故障 1 和 2（保证 commit 之后才发），但解决不了故障 3：
 *   commit 成功后、send 之前进程崩溃，消息就永久丢失了，而且【没有任何痕迹】——
 *   你甚至不知道丢了什么。
 *
 *   Outbox 把消息持久化了，所以进程随时可以崩，重启后 relay 会继续投递。
 *   这就是「可靠」和「大部分时候可靠」的区别。
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        // relay 的核心查询：找出还没发送的记录，按插入顺序处理。
        // 部分索引(WHERE sent_at IS NULL)在迁移脚本里创建 —— 这里只是声明给 Hibernate 看。
        // 为什么用部分索引：已发送的记录占 99.99%，但 relay 只关心未发送的那几条。
        // 全列索引会把上百万条已发送记录也索引进去，体积大、缓存命中率低；
        // 部分索引只有几十行，永远常驻内存
        @Index(name = "idx_outbox_unsent", columnList = "sentAt, id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 事件的业务唯一 ID，会放进消息体一起发给消费端做幂等去重。
     * 注意它和主键 id 是两回事：id 是这条 outbox 记录的标识，
     * eventId 是这个【领域事件】的标识。relay 重发时 id 不变、eventId 也不变，
     * 所以消费端能识别出这是同一个事件。
     */
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String topic;

    /**
     * Kafka 的分区键。
     * 用 orderId 做 key 能保证同一订单的所有事件落在同一分区，从而【严格有序】。
     * Kafka 只保证分区内有序，不保证跨分区有序 —— 这是最常被误解的一点。
     */
    @Column(name = "message_key", length = 100)
    private String messageKey;

    /**
     * 序列化后的事件 JSON。
     * 用 TEXT 而不是 JSONB：我们从不在数据库里查询 payload 的内部字段，
     * 它对数据库来说就是个不透明的字节串。JSONB 会付出解析和存储开销却没有收益。
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** 事件类型全限定名，仅用于排障时肉眼识别，不参与反序列化 */
    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** null = 待发送。发送成功后写入时间戳（软标记，保留记录用于排障和重放） */
    @Column(name = "sent_at")
    private Instant sentAt;

    /** 连续发送失败次数，用于告警和退避 */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
