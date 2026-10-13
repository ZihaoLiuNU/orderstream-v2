package com.orderstream.messaging.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderstream.common.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 消费者配置：并发度、错误处理、死信队列。
 */
@Slf4j
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * 消费并发度。
     *
     * 【这是原版代码最大的性能瓶颈】
     *   ConcurrentKafkaListenerContainerFactory 的 concurrency 默认是 1，
     *   意味着整个服务只有一个线程在消费。原版的 PaymentSimulator 里还有一句
     *   Thread.sleep(100~500ms) 阻塞这唯一的线程 ——
     *   吞吐直接被锁死在约 3 单/秒。500+ 并发根本跑不出来。
     *
     * 【设成多少合适】
     *   硬上限是 partition 数：一个 partition 在同一个消费者组内
     *   只能被一个消费者线程消费。concurrency 设成 10 但只有 3 个 partition，
     *   那就有 7 个线程永远空闲。
     *   所以这个值必须和 KafkaTopicsConfig 里的 partition 数一起设计。
     *
     * 【并发消费打破了什么保证】
     *   多线程消费后，不同 partition 的消息是并行处理的，
     *   全局顺序彻底没有了。但「同一订单的消息」因为 key 相同必然落同一 partition，
     *   所以单订单内仍然严格有序 —— 这才是业务真正需要的保证。
     *   用 key 换取「局部有序 + 全局并行」，是 Kafka 设计的精髓。
     */
    @Value("${orderstream.kafka.consumer-concurrency:3}")
    private int concurrency;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 统一收 String，再由 StringJsonMessageConverter 按 @KafkaListener 方法的
        // 参数类型反序列化。好处是不用为每种事件类型配一个 ContainerFactory
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        /*
         * earliest：新消费者组从最早的消息开始消费。
         *
         * 对比 latest（只消费启动之后产生的消息）：
         *   latest 的问题是，服务重新部署期间产生的消息会被【永久跳过】。
         *   对订单这种不能丢的业务，必须 earliest。
         *
         * 注意这个配置【只在消费者组第一次出现、没有已提交 offset 时生效】。
         * 已有 offset 时永远从 offset 继续，和这个配置无关。
         * 很多人误以为改成 earliest 就能重新消费历史消息，其实不会。
         */
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        /*
         * 关闭自动提交 offset。这是可靠消费的前提。
         *
         * 自动提交(enable.auto.commit=true)是按时间定期提交的，
         * 它完全不知道你的业务逻辑处理到哪了。典型故障：
         *   拉到 100 条 -> 自动提交定时器触发，提交了 offset -> 处理到第 50 条时崩溃
         *   -> 重启后从 101 开始 -> 【51~100 永久丢失】
         *
         * 关掉后，Spring Kafka 默认用 BATCH 模式：
         * 监听器方法正常返回后才提交 offset。处理失败则不提交，下次重新拉取。
         */
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // 单次 poll 最多拉 50 条。
        // 这个值 × 单条处理耗时 必须小于 max.poll.interval.ms(默认 5 分钟)，
        // 否则消费者会被判定为死亡并触发 rebalance，然后这批消息被重新分配给别人 ——
        // 表现为「消息一直在重复消费，永远处理不完」，是新手最常遇到的诡异现象
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);

        // 心跳与会话超时：3 秒一次心跳，10 秒没心跳判定死亡。
        // session.timeout 必须 >= 3 倍 heartbeat.interval，否则偶尔一次网络抖动
        // 就会触发不必要的 rebalance
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler,
            ObjectMapper objectMapper) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setCommonErrorHandler(errorHandler);

        // 关键：让 @KafkaListener 方法可以直接声明强类型参数，
        // 例如 public void onPaymentResult(PaymentResultEvent event)。
        // 转换器会根据方法签名推断目标类型再反序列化 ——
        // 这样就【不依赖消息头里的类型信息】。
        // 依赖类型头的问题是：生产者重构包名后，消费者就反序列化失败了，
        // 事件契约被包名这种实现细节绑架了
        factory.setRecordMessageConverter(new StringJsonMessageConverter(objectMapper));

        return factory;
    }

    /**
     * 错误处理 + 死信队列。
     *
     * ========================================================================
     * 【没有这个的后果 —— 原版代码就是这样】
     * ========================================================================
     * Spring Kafka 的默认 DefaultErrorHandler 是 FixedBackOff(0ms, 9次)：
     *   立刻重试 10 次，全失败后【打一条日志、提交 offset、消息永久消失】。
     *
     *   两个致命问题：
     *     1. 零间隔重试。下游数据库正在重启，10 次重试在几毫秒内跑完全部失败，
     *        等于没重试。而如果等 30 秒，数据库可能就恢复了。
     *     2. 最终静默丢弃。一笔支付结果就这么没了，订单永久卡在 PENDING，
     *        而日志里只有一行 ERROR，没人会看到。
     *
     * ========================================================================
     * 【正确做法：指数退避重试 + 死信队列】
     * ========================================================================
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {

        /*
         * 死信投递器：重试耗尽后，把消息原样发到 <原topic>.DLT。
         *
         * 死信队列的价值：
         *   - 消息【不丢】，可以事后人工检查、修复代码后重放
         *   - 消费流【不阻塞】：一条毒丸消息不会卡住整个 partition 后面所有消息
         *   - DLT 的消息量本身就是一个绝佳的健康指标，非零就该告警
         *
         * 保持相同的 partition 号：这样原 topic 里同 key 的消息
         * 和它在 DLT 里的位置是对应的，排障时容易关联
         */
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    log.error("[DLT] 消息重试耗尽，投递到死信队列 topic={} partition={} offset={} key={} error={}",
                            record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage());
                    return new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition());
                });

        /*
         * 指数退避：1s -> 2s -> 4s -> 8s，最多 4 次，总共约 15 秒。
         *
         * 为什么要退避而不是立刻重试：
         *   失败通常是因为下游资源紧张（数据库连接耗尽、服务过载）。
         *   立刻重试是【火上浇油】—— 在对方最脆弱的时候继续加压，
         *   这叫重试风暴(retry storm)，能把一次短暂抖动放大成全面雪崩。
         *   退避给了下游恢复的时间窗口。
         *
         * 生产环境还应该加随机抖动(jitter)：
         *   所有消费者同时失败、同时按相同节奏退避，会在同一时刻一起重试，
         *   形成周期性的流量尖峰。加随机偏移把重试打散开。
         */
        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(15_000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);

        /*
         * 【不可重试异常】：这些错误重试多少次都一样，直接进死信队列。
         *
         * 判断标准：这个失败是【瞬时的】还是【确定性的】？
         *   瞬时的（网络抖动、数据库连接超时、下游 503）-> 重试有意义
         *   确定性的（JSON 格式错误、字段缺失、业务规则不允许）-> 重试纯属浪费
         *
         * 对确定性错误做退避重试，只会把 15 秒的延迟强加给后面所有消息，
         * 而结果注定是失败。直接进 DLT 才是对的。
         */
        handler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class,
                com.orderstream.common.web.BusinessException.class,
                com.orderstream.common.web.ResourceNotFoundException.class
        );

        // 每次重试都记日志，否则重试过程完全不可见，排障时根本不知道发生过重试
        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("[重试] 第 {} 次 topic={} offset={} key={} error={}",
                        attempt, record.topic(), record.offset(), record.key(), ex.getMessage()));

        return handler;
    }
}
