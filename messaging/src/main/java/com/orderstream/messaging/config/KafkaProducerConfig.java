package com.orderstream.messaging.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 生产者配置。
 *
 * 全系统只有 OutboxRelay 和死信投递器会用到它 ——
 * 业务代码永远不直接发 Kafka，只往 outbox 表写。
 *
 * value 用 StringSerializer 而不是 JsonSerializer：
 *   payload 在写入 outbox 表时就已经序列化成 JSON 字符串了，
 *   这里再用 JsonSerializer 会把字符串【再序列化一次】，
 *   结果是带转义引号的双重编码 JSON，消费端解析会失败。
 *   这是个很容易踩的坑。
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        /*
         * acks=all：等【所有 in-sync 副本】都确认写入才算成功。
         *
         * 三个取值的区别：
         *   acks=0   发出去就不管了。最快，但 broker 挂了消息直接没了，连报错都没有
         *   acks=1   leader 写入就返回。leader 在副本同步完成前挂掉 -> 丢消息
         *   acks=all leader + 所有 ISR 都写入才返回。不丢，但延迟最高
         *
         * 订单和支付事件丢一条就是一笔钱，必须 all。
         *
         * 注意 acks=all 单独用是不够的：它只保证「所有 ISR 都写了」，
         * 如果 ISR 里只剩 leader 一个（其他副本都挂了/落后了），
         * 那 acks=all 退化成 acks=1。必须配合 broker 端的
         * min.insync.replicas=2 才有实际意义 —— 少于 2 个副本可用时直接拒绝写入，
         * 宁可不可用也不丢数据（CP 选择）。
         */
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        /*
         * 幂等 producer：防止【broker 端重试】造成的分区内消息重复。
         *
         * 场景：producer 发了消息，broker 写成功了，但返回 ack 的网络包丢了。
         *       producer 以为失败，重试一次 -> 同一条消息在分区里出现两次。
         *
         * 开启后，producer 给每条消息带上 (producerId, sequenceNumber)，
         * broker 发现序号重复就直接丢弃并返回成功。
         *
         * 代价几乎为零（Kafka 3.0+ 默认就是开的），写出来是为了明确意图。
         *
         * 注意它【只解决 producer 重试导致的重复】，不解决：
         *   - 应用层重复调用 send()
         *   - Outbox relay 重发
         *   - 消费端重复消费
         * 那些必须靠消费端幂等（ProcessedEvent 表）来兜。
         */
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // 开启幂等后 max.in.flight 必须 <= 5，否则 Kafka 启动时直接报错。
        // 原因：超过 5 条在途消息时，broker 无法维护足够的序号窗口来做去重和保序
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // 无限重试 + 交付超时兜底。
        // retries 设成 MAX_VALUE 不等于会永远重试 —— 真正的上限是 delivery.timeout.ms。
        // 这种写法比「retries=3」更好：3 次重试可能只覆盖 1 秒，
        // 而 broker 滚动重启通常要几十秒。用时间而不是次数来定义放弃点，更贴近真实故障
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);

        // 攒批：最多等 10ms 把同分区的消息合并成一个请求发出去。
        // 默认是 0（来一条发一条），吞吐很差。
        // 10ms 的延迟换来数倍吞吐提升，对异步事件流是划算的
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }
}
