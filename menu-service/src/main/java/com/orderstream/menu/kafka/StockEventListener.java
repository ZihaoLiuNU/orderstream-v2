package com.orderstream.menu.kafka;

import com.orderstream.common.Topics;
import com.orderstream.common.event.StockReleaseRequestedEvent;
import com.orderstream.common.event.StockReservationRequestedEvent;
import com.orderstream.menu.service.StockService;
import com.orderstream.messaging.idempotency.IdempotentEventProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * menu-service 作为 Saga 参与者的入口。
 *
 * 这个类刻意写得很薄 —— 它只负责"接消息 + 保证幂等 + 委托给 Service"。
 * 业务逻辑全在 StockService 里，这样单元测试可以直接测 StockService，
 * 完全不需要启动 Kafka。
 *
 * 一个常见的反模式是把业务逻辑直接写在 @KafkaListener 方法里，
 * 结果就是这段逻辑只能通过启动完整的 Kafka 环境来测试，
 * 测试又慢又不稳定，最后大家就干脆不测了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockEventListener {

    /**
     * 消费者组 ID。
     *
     * 它同时是 ProcessedEvent 表里的 consumer 字段值 ——
     * 这个一致性很重要：幂等去重的粒度必须和消费的粒度对齐。
     */
    private static final String CONSUMER_GROUP = "menu-service";

    private final StockService stockService;
    private final IdempotentEventProcessor idempotentProcessor;

    /**
     * Saga 步骤 1：预留库存。
     *
     * 【@Transactional 在这里是必需的，而且位置很关键】
     *   它必须加在【最外层】的监听方法上，这样：
     *     幂等检查 + 业务逻辑 + 写去重标记 + 写 outbox 事件
     *   全部在同一个事务里，一起提交或一起回滚。
     *
     *   如果只在 StockService.reserve 上加事务，
     *   那么幂等标记和业务逻辑就分属两个事务，
     *   在"业务提交了但标记没提交"的窗口期崩溃，重启后会重复扣库存。
     *
     * 【事务和 offset 提交的关系 —— 必须理解清楚】
     *   数据库事务提交 和 Kafka offset 提交 是【两个独立的动作】，无法原子化。
     *   顺序是：方法正常返回 -> 数据库事务提交 -> Spring Kafka 提交 offset。
     *   中间崩溃的话，数据库已提交但 offset 没提交 -> 重启后重复消费
     *   -> 靠幂等去重挡住。
     *   这就是为什么 exactly-once 做不到，只能做 effectively-once。
     */
    @KafkaListener(topics = Topics.STOCK_RESERVATION_REQUESTED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onReservationRequested(StockReservationRequestedEvent event) {
        log.info("[Saga-1] 收到库存预留请求 sagaId={} orderId={}", event.getSagaId(), event.getOrderId());
        idempotentProcessor.process(CONSUMER_GROUP, event,
                e -> stockService.reserve((StockReservationRequestedEvent) e));
    }

    /**
     * 补偿事务 1：释放库存。
     */
    @KafkaListener(topics = Topics.STOCK_RELEASE_REQUESTED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onReleaseRequested(StockReleaseRequestedEvent event) {
        log.info("[Saga-补偿] 收到库存释放请求 sagaId={} orderId={}", event.getSagaId(), event.getOrderId());
        idempotentProcessor.process(CONSUMER_GROUP, event,
                e -> stockService.release((StockReleaseRequestedEvent) e));
    }
}
