package com.orderstream.messaging.idempotency;

import com.orderstream.common.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * 幂等消费的统一入口。
 *
 * 用法：
 *
 *     @KafkaListener(topics = Topics.PAYMENT_RESULT, groupId = "saga-orchestrator")
 *     @Transactional
 *     public void onPaymentResult(PaymentResultEvent event) {
 *         idempotentProcessor.process("saga-orchestrator", event, e -> {
 *             // 这里的业务逻辑保证只会对同一个 eventId 执行一次
 *         });
 *     }
 *
 * 把「查重 + 执行 + 标记」封装在一个地方，好处是所有消费者的幂等行为完全一致，
 * 不会出现某个消费者忘了查重、或者查了重但忘了标记的情况。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentEventProcessor {

    private final ProcessedEventRepository processedEventRepository;

    /**
     * 幂等地处理一个事件。
     *
     * propagation = MANDATORY：和 OutboxPublisher 同理。
     *   必须由调用方（@KafkaListener 方法）开启事务，
     *   这样「业务变更」和「已处理标记」才在同一个事务里。
     *   如果这里自己开事务，两者就分属不同事务，幂等保证立刻失效。
     *
     * @param consumerId 消费者标识，通常用 consumer group id。见 ProcessedEvent 的注释
     * @param event      待处理事件
     * @param handler    真正的业务逻辑，保证对同一 eventId 只执行一次
     * @return true 表示执行了业务逻辑，false 表示是重复事件被跳过
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean process(String consumerId, DomainEvent event, Consumer<DomainEvent> handler) {

        String eventId = event.getEventId();

        /*
         * 【为什么先查一次，而不是直接 insert 靠唯一约束兜底】
         *
         * 两种写法都能保证正确性，但性能特征完全不同：
         *
         *   先查：重复事件走一次 SELECT 就返回了，代价极小。
         *         而重复事件在正常运行时是【少数】，所以绝大多数请求
         *         要多付一次 SELECT 的代价。
         *
         *   直接插：重复时会抛 DataIntegrityViolationException。
         *         但在 Postgres 里，一旦事务中有语句报错，
         *         【整个事务就进入 aborted 状态】，后续任何语句都会报
         *         "current transaction is aborted"。想继续就必须用 SAVEPOINT。
         *         这是 Postgres 和 MySQL 的一个重要区别，很多人栽在这。
         *
         * 所以这里用「先查 + 插入时再 catch 兜底」的组合：
         *   查询处理常规情况，catch 处理并发窗口（两个线程同时通过了查询）。
         */
        if (processedEventRepository.existsByEventIdAndConsumer(eventId, consumerId)) {
            log.info("[幂等] 重复事件已跳过 consumer={} eventId={} type={}",
                    consumerId, eventId, event.getClass().getSimpleName());
            return false;
        }

        // 先执行业务逻辑，再写标记。
        // 顺序其实无所谓（同一事务，一起提交），但这样读起来更符合直觉
        handler.accept(event);

        try {
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .consumer(consumerId)
                    .processedAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            /*
             * 走到这里说明：两个线程同时消费了同一个事件，都通过了上面的 exists 检查，
             * 都执行了业务逻辑，然后在插入标记时撞上了主键冲突。
             *
             * 这种情况【理论上不应该发生】—— Kafka 保证同一分区同一时刻
             * 只有一个消费者实例在处理。会发生只可能是：
             *   - rebalance 期间的短暂重叠
             *   - 消费者处理超时被踢出但仍在跑
             *
             * 抛出去让事务回滚是正确的：业务逻辑可能已经执行了一半，
             * 回滚掉、让 Kafka 重投一次，下次走幂等跳过分支。
             * 这正是「幂等 + 事务」组合的价值：任何时候崩溃都能安全重试。
             */
            log.warn("[幂等] 并发重复消费被数据库主键拦截 consumer={} eventId={}", consumerId, eventId);
            throw e;
        }

        return true;
    }

    /**
     * 清理旧的去重记录。
     *
     * 【保留期必须大于 Kafka 的 topic retention】—— 这是个容易想错的地方。
     *   如果 Kafka 保留 7 天的消息，而去重表只保留 1 天，
     *   那么一次「从头重放」(reset offset to earliest) 会把 6 天前的消息
     *   全部重新投递，而去重表已经不记得它们了 -> 全部被重复处理。
     *   这里保留 30 天，Kafka 保留 7 天，留足安全边际。
     */
    @Transactional
    public int purgeOlderThan(Duration retention) {
        return processedEventRepository.deleteProcessedBefore(Instant.now().minus(retention));
    }
}
