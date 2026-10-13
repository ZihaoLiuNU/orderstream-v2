package com.orderstream.messaging.idempotency;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 已处理事件的去重表。
 *
 * ============================================================================
 * 【为什么必须有它】
 * ============================================================================
 *
 * Kafka 的投递语义是 at-least-once，重复消费是【必然】发生的，不是偶发异常。
 * 三个必然重复的场景：
 *
 *   1. 消费者处理成功，但在提交 offset 前崩溃/被 rebalance 踢出 -> 重启后重新消费
 *   2. 消费者处理耗时超过 max.poll.interval.ms -> 被判定为死亡 -> 分区被分配给别人
 *      -> 别人从上次提交的 offset 重新消费（而原消费者可能还在处理！）
 *   3. Outbox relay 发送成功但标记 sent_at 前崩溃 -> 重启后重发
 *
 * 原版代码的 handlePaymentResult 完全没有去重，后果不只是「通知发两遍」：
 *
 *   时间线：支付成功 -> 订单 CONFIRMED -> 管理员推进到 PREPARING
 *          -> 旧的 PaymentResultEvent 重投 -> 无条件 setStatus(CONFIRMED)
 *          -> 【订单从 PREPARING 倒退回 CONFIRMED】
 *
 *   这是真正的数据损坏，而且它绕过了状态机校验（validateTransition 只保护了
 *   管理员那条路径，Saga 这条路径根本没走校验）。
 *
 * ============================================================================
 * 【为什么去重表能工作：关键在于和业务写在同一个事务里】
 * ============================================================================
 *
 *     @Transactional
 *     public void handle(Event e) {
 *         if (alreadyProcessed(e.getEventId())) return;   // 查去重表
 *         doBusinessLogic(e);                              // 改业务数据
 *         markProcessed(e.getEventId());                   // 写去重表
 *     }   // 三步一起提交
 *
 * 「业务变更」和「已处理标记」在同一个本地事务里，所以不可能出现
 * 「业务做了但没标记」或「标记了但业务没做」的中间态。
 * 这是 effectively-once 语义的标准实现 ——
 * 我们做不到 exactly-once 投递，但能做到 exactly-once 效果。
 *
 * ============================================================================
 * 【什么时候可以不用去重表】
 * ============================================================================
 *
 * 如果业务操作本身就天然幂等，就不需要这张表。例如：
 *   - SET status = 'CANCELLED'（赋值，不是累加）
 *   - DELETE FROM cart WHERE user_id = ?（删两次和删一次结果一样）
 * 反之绝对需要：
 *   - stock = stock - 1（累加，执行两次就多扣一份）
 *   - 扣款、发通知、发短信（有外部副作用）
 *
 * 判断标准是：f(f(x)) == f(x) 是否成立。
 * 这个判断要逐个事件做，不能一刀切。
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcessedEvent {

    /**
     * 复合主键的第一部分：事件 ID。
     */
    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    /**
     * 复合主键的第二部分：消费者标识（通常是 consumer group id）。
     *
     * 【为什么主键必须是 (eventId, consumer) 而不只是 eventId】
     *   同一个事件会被【多个】不同的消费者组消费 —— 这正是 Kafka 发布订阅的意义。
     *   比如 order.status.changed 同时被 notification-service 和
     *   cart-service 消费。
     *   如果主键只有 eventId，notification 处理完写了一行，
     *   cart 来查的时候会发现「已处理」而直接跳过 —— 整个下游服务静默失效。
     *
     *   这是个非常隐蔽的 bug：功能看起来是好的（因为第一个消费者正常工作），
     *   只有第二个消费者的功能莫名其妙不生效，而且没有任何错误日志。
     */
    @Id
    @Column(name = "consumer", length = 100)
    private String consumer;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (processedAt == null) processedAt = Instant.now();
    }

    /** JPA 复合主键类：必须有无参构造、equals、hashCode，且实现 Serializable */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Key implements java.io.Serializable {
        private String eventId;
        private String consumer;
    }
}
