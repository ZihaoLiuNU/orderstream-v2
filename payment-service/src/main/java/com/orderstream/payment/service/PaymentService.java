package com.orderstream.payment.service;

import com.orderstream.common.Topics;
import com.orderstream.common.event.PaymentRefundRequestedEvent;
import com.orderstream.common.event.PaymentRequestedEvent;
import com.orderstream.common.event.PaymentResultEvent;
import com.orderstream.messaging.outbox.OutboxPublisher;
import com.orderstream.payment.entity.Payment;
import com.orderstream.payment.entity.Refund;
import com.orderstream.payment.repository.PaymentRepository;
import com.orderstream.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付服务 —— Saga 步骤 2 的参与者。
 *
 * 这是一个【模拟】的支付网关。真实实现会调用 Stripe / Adyen / 支付宝的 SDK，
 * 但围绕它的所有工程问题（幂等、超时、退款、对账）都是一样的，
 * 所以这部分代码的结构和真实实现没有区别。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OutboxPublisher outboxPublisher;

    /**
     * 模拟的支付网关延迟。
     *
     * ========================================================================
     * 【这个参数直接决定了系统的吞吐上限 —— 原版代码在这里有个严重问题】
     * ========================================================================
     *
     * 原版是写死的 Thread.sleep(100 + random(400))，平均 300ms，
     * 而 Kafka 消费者的 concurrency 用的是默认值 1（单线程）。
     *
     *   吞吐上限 = 1 / 0.3 秒 ≈ 3.3 单/秒
     *
     * "500+ 并发订单"在这个配置下要跑 150 秒，
     * 一算就会发现讲不通。
     *
     * 【正确的解决思路不是"去掉延迟"】—— 真实支付网关就是 200~500ms，
     * 这个延迟是客观存在的，假装它不存在等于在自欺欺人。
     *
     * 正确做法是【提高并行度】：
     *   1) topic 分成 6 个分区（见 KafkaTopicsConfig）
     *   2) consumer-concurrency 设为 6（见 application.yml）
     *   3) 需要更高吞吐就再加 payment-service 实例，
     *      Kafka 会自动把分区重新分配给新实例
     *
     *   6 个线程 × (1/0.05秒) = 120 单/秒。
     *   再起 3 个实例就是 360 单/秒 —— 这才是"水平扩展"的含义。
     *
     * 这里默认值调到 50ms，是为了让压测能在合理时间内完成；
     * 压测报告里会同时给出 50ms 和 300ms 两组数据，说明瓶颈在哪。
     */
    @Value("${orderstream.payment.simulated-latency-ms:50}")
    private int simulatedLatencyMs;

    /** 模拟失败率。用来验证 Saga 的补偿路径确实会被触发并正确执行 */
    @Value("${orderstream.payment.failure-rate:0.1}")
    private double failureRate;

    // ======================================================================
    // 扣款
    // ======================================================================

    /**
     * 处理支付请求。
     *
     * ========================================================================
     * 【三层幂等防护，缺一不可】
     * ========================================================================
     *
     *   第 1 层：ProcessedEvent 表按 eventId 去重（在 PaymentEventListener 里）
     *           防的是：同一条 Kafka 消息被投递两次
     *
     *   第 2 层：下面的 findByIdempotencyKey 查询
     *           防的是：业务上对同一个 Saga 发起了两次支付请求
     *                  （比如 order-service 因为某种原因重发了）
     *
     *   第 3 层：数据库唯一约束 + catch DataIntegrityViolationException
     *           防的是：第 2 层的查询和插入之间的并发窗口
     *
     * 为什么三层都要：
     *   第 1 层和第 2 层是【不同粒度】的去重，防的是不同的重复来源。
     *   第 3 层是唯一在【并发】下可靠的 —— 前两层都是 check-then-act，
     *   理论上都有竞态窗口。
     *
     *   在"绝不能重复扣款"这种场景下，多一层冗余是完全值得的。
     *   一次重复扣款造成的信任损失，远大于多写 10 行代码的成本。
     */
    @Transactional
    public void processPayment(PaymentRequestedEvent event) {

        // ---- 第 2 层幂等：这笔支付已经处理过了吗 ----
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(event.getIdempotencyKey());
        if (existing.isPresent()) {
            Payment payment = existing.get();
            log.info("[支付] 幂等命中，重发原结果 idempotencyKey={} paymentId={} status={}",
                    event.getIdempotencyKey(), payment.getId(), payment.getStatus());

            /*
             * 【重发原结果，而不是静默返回】—— 这一点很关键。
             *
             * 如果只是 return 什么都不做，会出现这种死局：
             *   第一次支付成功了，但结果消息在投递途中丢了（或者编排器没收到）。
             *   order-service 超时后重发支付请求。
             *   payment-service 一看"处理过了"，直接 return。
             *   -> order-service 永远等不到回执，订单永久卡死。
             *
             * 重发原结果就打破了这个死局：
             * 无论请求重复多少次，调用方总能拿到一个确定的答复。
             *
             * 这是幂等设计里最容易漏的一点：
             * 【幂等不只是"不重复执行副作用"，还包括"重复请求要给出相同的响应"】。
             */
            publishResult(payment, event.getSagaId());
            return;
        }

        // ---- 调用（模拟的）支付网关 ----
        simulateGatewayLatency();
        boolean success = ThreadLocalRandom.current().nextDouble() >= failureRate;

        Payment payment = Payment.builder()
                .orderId(event.getOrderId())
                .userId(event.getUserId())
                .amount(event.getAmount())
                .idempotencyKey(event.getIdempotencyKey())
                .status(success ? Payment.Status.SUCCEEDED : Payment.Status.FAILED)
                .failureReason(success ? null : "账户余额不足，支付被拒绝")
                .externalTxnId(success ? "txn_" + UUID.randomUUID().toString().substring(0, 16) : null)
                .build();

        try {
            payment = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException e) {
            /*
             * ---- 第 3 层幂等：唯一约束兜住了并发窗口 ----
             *
             * 走到这里说明：两个线程同时处理同一个幂等键，
             * 都通过了上面的 findByIdempotencyKey 检查，然后同时插入。
             *
             * 数据库拦下了第二个。我们重新查出第一个线程写入的记录并返回它的结果。
             *
             * 【注意这里有个 Postgres 特有的坑】：
             *   Postgres 里一旦事务中有语句报错，整个事务会进入 aborted 状态，
             *   后续任何语句都会报 "current transaction is aborted"。
             *   所以严格来说，这里的重新查询需要 Spring 帮我们在新事务里做，
             *   或者上层捕获后重试。
             *   我的做法是：抛出去让事务回滚 + Kafka 重投，
             *   重投时会走上面的"第 2 层幂等命中"分支正常返回。
             *   这比在已中止的事务里挣扎要干净得多。
             */
            log.warn("[支付] 并发重复请求被唯一约束拦截，将由 Kafka 重投后走幂等分支 key={}",
                    event.getIdempotencyKey());
            throw e;
        }

        log.info("[支付] {} orderId={} paymentId={} 金额={} key={}",
                success ? "成功" : "失败", event.getOrderId(), payment.getId(),
                event.getAmount(), event.getIdempotencyKey());

        // 回报结果。走 outbox，保证"扣款记录"和"通知编排器"的原子性
        publishResult(payment, event.getSagaId());
    }

    // ======================================================================
    // 退款（补偿事务）
    // ======================================================================

    /**
     * 执行退款补偿。
     *
     * 【补偿事务的三个硬性要求，逐条对照】
     *   1. 幂等   —— refunds.idempotency_key 唯一约束 + 前置查询
     *   2. 不失败 —— 找不到原支付时安全返回，而不是抛异常。
     *               补偿失败了没有"补偿的补偿"，只能重试或人工介入，
     *               所以要把一切"可以安全忽略"的情况都识别出来
     *   3. 语义补偿而非回滚 —— 新增一条退款流水，原支付记录原样保留
     */
    @Transactional
    public void processRefund(PaymentRefundRequestedEvent event) {

        // 幂等键用 "refund:" 前缀 + sagaId。
        // 加前缀是为了和支付的幂等键区分开 —— 虽然在不同的表，
        // 但保持命名空间清晰能避免将来合并存储时撞车
        String refundKey = "refund:" + event.getSagaId();

        if (refundRepository.findByIdempotencyKey(refundKey).isPresent()) {
            log.info("[退款] 幂等命中，跳过 key={}", refundKey);
            return;
        }

        Optional<Payment> paymentOpt = paymentRepository.findById(event.getPaymentId());

        if (paymentOpt.isEmpty()) {
            /*
             * 找不到原支付记录。
             *
             * 这【不是】错误。正常场景：支付本来就失败了，
             * 但编排器仍然统一发出了补偿指令
             * （编排器不精确记录每一步的成败，统一发补偿更简单也更健壮）。
             *
             * 补偿事务必须能处理"要补偿的事根本没发生过"这种情况。
             * 如果这里抛异常，Kafka 会重试 -> 耗尽 -> 进死信队列 -> 误报警，
             * 值班的人被一个完全正常的流程叫醒。
             */
            log.info("[退款] 无需退款，原支付记录不存在 paymentId={}（通常是支付本身就失败了）",
                    event.getPaymentId());
            return;
        }

        Payment payment = paymentOpt.get();

        if (payment.getStatus() != Payment.Status.SUCCEEDED) {
            log.info("[退款] 无需退款，原支付状态为 {} paymentId={}",
                    payment.getStatus(), payment.getId());
            return;
        }

        // 追加一条退款流水（append-only，不改原支付记录的金额）
        refundRepository.save(Refund.builder()
                .paymentId(payment.getId())
                .orderId(event.getOrderId())
                .amount(payment.getAmount())
                .reason(event.getReason())
                .idempotencyKey(refundKey)
                .build());

        // 原支付记录只改一个【状态标记】，用于快速查询"这笔钱退了没"。
        // 金额、时间、交易号全部保持不变 —— 历史事实不可篡改
        payment.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(payment);

        log.info("[退款] 补偿完成 orderId={} paymentId={} 金额={} 原因={}",
                event.getOrderId(), payment.getId(), payment.getAmount(), event.getReason());
    }

    // ======================================================================

    private void publishResult(Payment payment, String sagaId) {
        outboxPublisher.publish(
                Topics.PAYMENT_RESULT,
                // 用 orderId 做分区键，保证同一订单的事件严格有序
                String.valueOf(payment.getOrderId()),
                PaymentResultEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(payment.getOrderId())
                        .sagaId(sagaId)
                        .paymentId(payment.getId())
                        .success(payment.getStatus() == Payment.Status.SUCCEEDED)
                        .failureReason(payment.getFailureReason())
                        .build());
    }

    /**
     * 模拟支付网关的网络往返延迟。
     *
     * 【Thread.sleep 在这里有个 Java 21 特有的注意点】
     *   如果这段代码跑在虚拟线程上，sleep 会让虚拟线程从载体线程上卸载，
     *   载体线程可以去跑别的任务 —— 完全不浪费。
     *   但 Spring Kafka 的监听容器默认用的是【平台线程】，
     *   所以这里的 sleep 是真的占着一个 OS 线程在等。
     *
     *   这就是为什么 consumer-concurrency 必须调大：
     *   在平台线程模型下，并发度 = 线程数，没有别的办法。
     *
     *   （Spring Boot 3.2 可以通过设置监听容器的 TaskExecutor 为
     *    虚拟线程执行器来改善，但 Kafka 消费者本身的分区分配模型
     *    仍然限制了并行度上限 = 分区数，所以分区数才是真正的天花板。）
     */
    private void simulateGatewayLatency() {
        if (simulatedLatencyMs <= 0) return;
        try {
            Thread.sleep(simulatedLatencyMs);
        } catch (InterruptedException e) {
            // 恢复中断标志，否则上层（以及优雅停机逻辑）永远不知道有人请求过中断
            Thread.currentThread().interrupt();
        }
    }
}
