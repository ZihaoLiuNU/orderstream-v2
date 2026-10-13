package com.orderstream.messaging.config;

import com.orderstream.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;


/**
 * 显式声明所有 topic 及其分区/副本配置。
 *
 * 【为什么不靠自动创建】
 *   broker 端 auto.create.topics.enable 默认是 true，第一次往一个不存在的 topic
 *   发消息时会自动创建。这在生产环境是灾难：
 *
 *     1. 拼错 topic 名（"payment.reslut"）会静默创建一个新 topic，
 *        消息发进去再也没人消费。没有任何报错，只是功能"莫名其妙不工作"。
 *     2. 自动创建的 topic 用的是 broker 默认配置 ——
 *        通常是 1 个分区。等你发现吞吐上不去，已经有大量数据在里面了，
 *        而【分区数只能增加不能减少】，且增加分区会破坏已有的 key -> partition 映射，
 *        导致同一订单的新旧消息落到不同分区，顺序保证直接失效。
 *
 *   所以 docker-compose 里我把 auto.create.topics.enable 设成了 false，
 *   topic 必须在这里显式声明。分区数是要提前按目标吞吐算的架构决策，
 *   不是可以事后补救的实现细节。
 *
 * 【分区数怎么定】
 *   分区数 = 消费端需要的并行度。因为一个分区在同一消费者组内
 *   只能被一个线程消费，分区数就是并行度的硬上限。
 *
 *   我按目标 500 并发在途订单、单条处理约 50ms 估算：
 *     需要的并发度 ≈ 目标吞吐(100/s) × 处理耗时(0.05s) ≈ 5
 *   取 6 个分区留一倍余量，并且 6 能被 1/2/3/6 个消费者实例整除，
 *   扩缩容时分区分配是均匀的（分区数最好能被常见实例数整除，
 *   否则会有实例分到的分区比别人多，形成木桶效应）。
 *
 *   不是越多越好：每个分区在每个 broker 上都是一组文件 + 内存缓冲，
 *   分区过多会拖慢 broker 启动、增加 controller 选举时间、放大 rebalance 开销。
 */
@Configuration
public class KafkaTopicsConfig {

    /** 单机开发只能 1 副本。生产至少 3，并配合 min.insync.replicas=2 */
    private static final int REPLICAS = 1;
    private static final int PARTITIONS = 6;

    /**
     * Saga 相关的业务 topic。
     *
     * KafkaAdmin 会在应用启动时比对并创建缺失的 topic。
     * 这个操作是幂等的：topic 已存在就跳过，所以每个服务都声明一遍也没问题，
     * 反而更健壮（任何一个服务起来都能自愈缺失的 topic）。
     *
     * 注意：已存在的 topic【不会】被修改成这里的配置。
     * 想改分区数必须用 kafka-topics --alter 手动操作，并接受 key 映射被打乱的后果。
     *
     * ========================================================================
     * 【返回 KafkaAdmin.NewTopics 而不是 List&lt;NewTopic&gt; —— 这是个真实踩过的坑】
     * ========================================================================
     *
     *   KafkaAdmin 在启动时扫描容器里的 Bean 来决定要创建哪些 topic，
     *   它只认两种类型：
     *     - 单个 NewTopic Bean
     *     - KafkaAdmin.NewTopics Bean（它内部持有一组 NewTopic）
     *
     *   【它不会展开 List&lt;NewTopic&gt; 类型的 Bean】。
     *
     *   我第一版就是返回 List&lt;NewTopic&gt;，结果是：
     *     - 应用正常启动，没有任何报错或警告
     *     - topic 一个也没创建
     *     - 消费者疯狂刷 UNKNOWN_TOPIC_OR_PARTITION 警告
     *     - 生产者发消息时因为 auto.create.topics.enable=false 而失败
     *
     *   这个 bug 的隐蔽之处在于：启动日志完全正常。
     *   只有真正跑一次端到端流程才会暴露 ——
     *   这正是为什么单元测试再全也替代不了集成/冒烟测试：
     *   单元测试验证的是【我写的逻辑对不对】，
     *   集成测试验证的是【我对框架的理解对不对】。
     *   而后者恰恰是最容易出错的地方，因为它依赖的是文档之外的隐式约定。
     */
    @Bean
    public KafkaAdmin.NewTopics orderStreamTopics() {
        return new KafkaAdmin.NewTopics(
                topic(Topics.STOCK_RESERVATION_REQUESTED),
                topic(Topics.STOCK_RESERVATION_RESULT),
                topic(Topics.STOCK_RELEASE_REQUESTED),
                topic(Topics.PAYMENT_REQUESTED),
                topic(Topics.PAYMENT_RESULT),
                topic(Topics.PAYMENT_REFUND_REQUESTED),
                topic(Topics.ORDER_STATUS_CHANGED),

                // 每个业务 topic 配一个死信 topic。
                // 分区数保持一致，这样原消息的 partition 号在 DLT 里依然有意义
                dlt(Topics.STOCK_RESERVATION_REQUESTED),
                dlt(Topics.STOCK_RESERVATION_RESULT),
                dlt(Topics.STOCK_RELEASE_REQUESTED),
                dlt(Topics.PAYMENT_REQUESTED),
                dlt(Topics.PAYMENT_RESULT),
                dlt(Topics.PAYMENT_REFUND_REQUESTED),
                dlt(Topics.ORDER_STATUS_CHANGED)
        );
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))  // 保留 7 天
                // 保留期的意义不只是省磁盘：它决定了「能回溯多久」。
                // 下游服务出 bug 修好后，可以 reset offset 重放这 7 天的事件来修复数据。
                // 注意 ProcessedEvent 去重表的保留期(30天)必须大于这个值，
                // 否则重放时老事件会因为去重记录已被清理而被重复处理
                .config("min.insync.replicas", "1")   // 生产环境应为 2
                .build();
    }

    private NewTopic dlt(String name) {
        return TopicBuilder.name(name + Topics.DLT_SUFFIX)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                // 死信保留更久：人工排查和修复需要时间，7 天可能不够
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}
