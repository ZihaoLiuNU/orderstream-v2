package com.orderstream.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderstream.common.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 业务代码发事件的唯一入口。
 *
 * 用法（在业务的 @Transactional 方法里调用）：
 *
 *     @Transactional
 *     public Order createOrder(...) {
 *         Order order = orderRepository.save(...);
 *         outboxPublisher.publish(Topics.PAYMENT_REQUESTED,
 *                                 String.valueOf(order.getId()),
 *                                 paymentRequestedEvent);
 *         return order;
 *     }
 *
 * 这个方法【不会】真的发消息到 Kafka，它只是往 outbox_events 表插一行。
 * 真正的投递由 OutboxRelay 异步完成。
 *
 * 对调用方来说，这个区别是透明的但语义上至关重要：
 *   「插一行」和「保存订单」在同一个事务里，要么都成功要么都失败。
 *   业务代码因此可以安心地假设：只要订单存了下来，事件就一定会被投递出去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 把事件写入 outbox 表。
     *
     * 【propagation = MANDATORY 是这个类最重要的一个设计决策】
     *
     * MANDATORY 的语义是：「必须已经有一个事务在运行，否则直接抛异常」。
     *
     * 为什么不用默认的 REQUIRED（没有事务就新建一个）：
     *   如果有人忘了在业务方法上加 @Transactional，REQUIRED 会【悄悄】为这次
     *   insert 单独开一个事务并提交。结果就是：业务数据的事务回滚了，
     *   但事件已经独立提交进了 outbox，relay 照样会把它发出去 ——
     *   Outbox 模式提供的原子性保证被静默地破坏了，而且没有任何报错。
     *
     *   MANDATORY 让这种误用在【第一次调用时就立刻抛异常】，
     *   在测试阶段就会被发现，绝不可能带到生产。
     *
     *   这是一个通用的设计思路：当一个组件的正确性依赖调用方满足某个前提时，
     *   要让违反前提的行为【尽早且响亮地失败】，而不是默默降级。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String messageKey, DomainEvent event) {

        // 双重保险：MANDATORY 已经保证了事务存在，这里再断言一次事务是【活跃】的。
        // 两者的区别在于极端情况：事务已被标记为 rollback-only 时仍然"存在"但无意义
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "OutboxPublisher.publish 必须在活跃事务中调用，否则无法保证业务数据与事件的原子性");
        }

        try {
            String payload = objectMapper.writeValueAsString(event);

            outboxEventRepository.save(OutboxEvent.builder()
                    .eventId(event.getEventId())
                    .topic(topic)
                    .messageKey(messageKey)
                    .payload(payload)
                    .eventType(event.getClass().getName())
                    .build());

            log.debug("[Outbox] 已入库 eventId={} topic={} key={}", event.getEventId(), topic, messageKey);

        } catch (JsonProcessingException e) {
            // 序列化失败是编程错误（事件类结构有问题），不是可恢复的运行时异常。
            // 抛出去让整个事务回滚 —— 业务操作也不该成功，
            // 因为它产生了一个无法传达给下游的状态变更
            throw new IllegalStateException("事件序列化失败: " + event.getClass().getName(), e);
        }
    }
}
