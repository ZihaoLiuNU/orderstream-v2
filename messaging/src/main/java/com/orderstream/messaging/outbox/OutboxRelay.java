package com.orderstream.messaging.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.support.SendResult;

/**
 * Outbox 投递器：把 outbox 表里未发送的事件推到 Kafka。
 *
 * 这是 Outbox 模式里【唯一】和 Kafka 打交道的地方。
 * 业务代码只管往表里写，投递的可靠性全部封装在这个类里。
 */
@Slf4j
@Component
// 允许在测试里关掉 relay，否则测试会真的去连 Kafka
@ConditionalOnProperty(name = "orderstream.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(OutboxEventRepository repository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       ObjectProvider<MeterRegistry> meterRegistry,
                       @Value("${orderstream.outbox.relay.batch-size:100}") int batchSize,
                       @Value("${orderstream.outbox.relay.send-timeout:5s}") Duration sendTimeout) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /**
     * 轮询并投递。
     *
     * 【为什么整个方法在一个事务里】
     *   SELECT ... FOR UPDATE SKIP LOCKED 拿到的行锁，必须一直持有到事务结束。
     *   如果在事务外发送，锁早就释放了，别的实例会捞到同一批记录重复发送。
     *   事务边界 == 锁的持有期 == 「这批记录归我处理」的宣告期。
     *
     * 【为什么用 fixedDelay 而不是 fixedRate】
     *   fixedRate 是固定频率启动，上一轮没跑完下一轮也会启动，
     *   Kafka 抖动导致发送变慢时会造成任务堆积。
     *   fixedDelay 保证上一轮结束后才开始计时，同一实例内绝不重叠。
     *
     * 【500ms 这个间隔是怎么定的】
     *   它直接决定了事件投递的额外延迟（平均 250ms，最坏 500ms）。
     *   - 调小：延迟低，但空转的查询变多（大部分时候表是空的）。
     *     部分索引(WHERE sent_at IS NULL)让空查询极廉价，所以代价不大。
     *   - 调大：省资源，但下单到支付启动的延迟变长，用户能感知到。
     *   要彻底消除这个延迟只能上 CDC（Debezium 读 Postgres WAL），
     *   代价是多一个 Kafka Connect 集群要运维。对当前规模不值得。
     */
    @Scheduled(fixedDelayString = "${orderstream.outbox.relay.interval:500}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.lockUnsentBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        /*
         * ====================================================================
         * 【整批并发发送，然后统一等待 —— 这是压测之后改的，提升了约 20 倍】
         * ====================================================================
         *
         * 【第一版是怎么写的，为什么慢】
         *   第一版在循环里逐条 send(...).get(timeout)：
         *
         *       for (event : batch) {
         *           kafkaTemplate.send(...).get(5s);   // 发一条，等一条的 ack
         *           event.setSentAt(now);
         *       }
         *
         *   看起来没问题，实际上把 100 条消息变成了【100 次串行的网络往返】。
         *   每次往返在 acks=all 下要等所有副本确认，约 10~20ms，
         *   一批 100 条就要 1~2 秒。加上 500ms 轮询间隔，
         *   实际吞吐只有【约 30 条/秒】。
         *
         *   500 并发压测产生了 6.4 万条待发事件，按这个速度要排空 35 分钟 ——
         *   Outbox 从"可靠性保障"变成了系统的吞吐瓶颈。
         *
         * 【为什么这个问题只有压测才能发现】
         *   功能测试、冒烟测试里每次只有几条事件，逐条等待完全看不出问题。
         *   它只在【积压】时才暴露。这正是压测不可替代的价值：
         *   有些设计缺陷只在规模上去之后才成为缺陷。
         *
         * 【改法：先全部发出，再统一等待】
         *   send() 本身是异步的，返回 CompletableFuture。
         *   先把整批都丢给 producer（它会按 linger.ms=10 和 batch.size
         *   自动合并成少数几个网络请求），然后再逐个 join。
         *
         *   往返次数从 100 次降到几次，而【可靠性保证完全不变】：
         *   我们依然在标记 sent_at 之前确认了每一条消息的 ack。
         *
         * 【关键：不能为了快而放弃等待】
         *   如果不等 ack 就直接标记已发送，那么 broker 不可用时
         *   消息会在 producer 缓冲区里被静默丢弃，而我们已经把它标记成
         *   "发送成功"了 —— 数据永久丢失且无从追溯。
         *   这里做的是【消除串行等待】，不是【取消等待】。两者有本质区别。
         */
        List<SendAttempt> attempts = new ArrayList<>(batch.size());

        // 阶段 1：把整批消息交给 producer，不等待
        for (OutboxEvent event : batch) {
            try {
                var future = kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload());
                attempts.add(new SendAttempt(event, future));
            } catch (Exception e) {
                // send() 本身抛异常（序列化失败、producer 已关闭）属于同步错误
                recordFailure(event, e);
            }
        }

        // 阶段 2：统一等待所有 ack
        int sent = 0;
        long deadline = System.nanoTime() + sendTimeout.toNanos();

        for (SendAttempt attempt : attempts) {
            try {
                // 用【剩余时间】而不是每条都给满 sendTimeout，
                // 否则整批的最坏耗时会是 100 × 5 秒 = 8 分钟，
                // 期间 relay 事务一直开着、行锁一直持有 —— 又回到了串行的老问题
                long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                attempt.future().get(remainingMs, TimeUnit.MILLISECONDS);

                attempt.event().setSentAt(Instant.now());
                attempt.event().setLastError(null);
                sent++;

            } catch (Exception e) {
                // 单条失败不影响同批次其他消息（毒丸消息隔离）。
                // sent_at 保持 null，下一轮 relay 会重试
                recordFailure(attempt.event(), e);
            }
        }

        // JPA 的脏检查会在事务提交时自动 UPDATE 被修改的实体，不需要显式 save()。
        // 写出来只是为了让读代码的人明确知道这里会发生写操作
        repository.saveAll(batch);

        if (sent > 0) {
            log.debug("[Outbox] 本轮投递 {}/{} 条", sent, batch.size());
        }
    }

    /**
     * 积压监控。
     *
     * 这是 Outbox 模式最重要的一个运维指标：
     *   unsent 数量持续上涨 = relay 挂了 / Kafka 不可用 / 有毒丸消息卡住。
     *   任何一种都意味着【下游服务正在静默地失去数据】，必须立刻告警。
     *
     * 暴露成 Prometheus 指标后，可以配一条告警规则：
     *   orderstream_outbox_unsent > 1000 持续 5 分钟 -> 呼叫值班
     */
    @Scheduled(fixedDelay = 30_000)
    public void reportBacklog() {
        if (meterRegistry == null) return;
        long unsent = repository.countUnsent();
        meterRegistry.gauge("orderstream.outbox.unsent", unsent);
        if (unsent > 1000) {
            log.error("[Outbox] 积压告警：{} 条事件待发送，下游可能正在丢失数据", unsent);
        }
    }

    /**
     * 清理已发送的旧记录。保留 7 天用于排障和事件重放。
     */
    @Scheduled(cron = "0 30 3 * * *")   // 每天凌晨 3:30，避开业务高峰
    @Transactional
    public void purgeSentEvents() {
        int deleted = repository.deleteSentBefore(Instant.now().minus(Duration.ofDays(7)));
        if (deleted > 0) {
            log.info("[Outbox] 清理 {} 条 7 天前已发送的事件记录", deleted);
        }
    }

    /**
     * 记录一次投递失败。
     *
     * 抽成方法是因为有两个地方会失败（同步的 send 抛异常、异步的 ack 超时），
     * 两处的处理必须完全一致 —— 否则某条路径忘了累加 attemptCount，
     * 那条消息就会无限重试且永远不触发告警。
     */
    private void recordFailure(OutboxEvent event, Exception e) {
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setLastError(truncate(e.getMessage()));

        // 连续失败 10 次基本不可能自愈（多半是毒丸消息或配置错误），
        // 升级成 error 级别让告警规则捞出来，而不是继续默默重试
        if (event.getAttemptCount() >= 10) {
            log.error("[Outbox] 事件已连续失败 {} 次，需要人工介入 eventId={} topic={} error={}",
                    event.getAttemptCount(), event.getEventId(), event.getTopic(), e.getMessage());
        } else {
            log.warn("[Outbox] 投递失败(第 {} 次) eventId={} topic={} error={}",
                    event.getAttemptCount(), event.getEventId(), event.getTopic(), e.getMessage());
        }
    }

    /** 一条消息的在途投递：事件本身 + 它的 ack future */
    private record SendAttempt(OutboxEvent event, CompletableFuture<SendResult<String, String>> future) {}

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
