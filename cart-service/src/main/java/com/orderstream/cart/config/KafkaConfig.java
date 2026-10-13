package com.orderstream.cart.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.util.backoff.ExponentialBackOff;

import com.orderstream.common.Topics;

/**
 * cart-service 自己的 Kafka 配置。
 *
 * ============================================================================
 * 【为什么不复用 messaging 模块里那套 —— 一个真实的依赖取舍】
 * ============================================================================
 *
 *   messaging 模块依赖 spring-boot-starter-data-jpa（因为 outbox 和
 *   幂等去重表都需要 JPA）。
 *
 *   cart-service 没有关系数据库，只用 Redis。
 *   如果引入 messaging：
 *     1) Spring Boot 检测到 classpath 上有 JPA -> 触发 DataSource 自动配置
 *     2) 找不到数据库连接信息 -> 启动直接失败
 *     3) 就算配上 exclude，也拖进了一堆用不到的依赖，拖慢启动、增大镜像
 *
 *   所以这里写了一份精简版：只有消费所需的最小配置，几十行。
 *
 * 【这说明了共享库设计的一个常见问题】
 *   把"Kafka 配置"和"Outbox/JPA"捆在一个模块里，
 *   就强迫所有想用前者的服务也接受后者。
 *   更好的拆法是三个模块：messaging-kafka / messaging-outbox / messaging-idempotency。
 *
 *   我没这么拆，是因为 7 个服务里只有 cart 这一个例外，
 *   为一个例外拆三个模块，维护成本大于收益。
 *   但代价是实打实的：就是眼前这个文件 —— 一份和 messaging 里
 *   高度相似、将来可能漂移的重复配置。
 *
 *   【这个取舍没有标准答案，重要的是能说清楚代价在哪】。
 */
@Slf4j
@EnableKafka
@Configuration
public class KafkaConfig {

    /**
     * 监听容器工厂。
     *
     * 【这里没有用 Spring Boot 的 ConcurrentKafkaListenerContainerFactoryConfigurer】
     *   那个扩展点的签名是 configure(factory, ConsumerFactory&lt;Object,Object&gt;)，
     *   而我们要的是 &lt;String,String&gt; 的工厂。Java 泛型是不协变的
     *   （List&lt;String&gt; 不是 List&lt;Object&gt; 的子类型），所以传不进去，
     *   只能靠裸类型强转绕过 —— 那会丢掉全部类型安全，得不偿失。
     *
     *   直接用注入进来的 ConsumerFactory 自己组装更清晰：
     *   Spring Boot 仍然从 application.yml 里读取了所有 spring.kafka.consumer.*
     *   配置来构造这个 ConsumerFactory，我们只是在它之上加两样东西。
     *
     * 【这是个小而真实的教训】：
     *   框架提供的"便捷扩展点"不总是适配你的场景。
     *   当为了用它而需要类型强转/反射时，通常说明走错路了 ——
     *   退回到显式组装反而更短更安全。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler,
            ObjectMapper objectMapper) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);

        // 让 @KafkaListener 方法可以直接声明强类型参数，
        // 转换器按方法签名推断目标类型，不依赖消息头里的类型信息。
        // 不依赖类型头很重要：生产者重构包名时，消费者不会跟着反序列化失败
        factory.setRecordMessageConverter(new StringJsonMessageConverter(objectMapper));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /**
     * 错误处理：指数退避重试 + 死信队列。
     *
     * 和 messaging 模块里的配置保持一致，理由也一样：
     *   Spring Kafka 的默认行为（立刻重试 10 次后静默丢弃）
     *   在任何生产场景下都是不可接受的。
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {

        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    log.error("[DLT] 消息重试耗尽，投递到死信队列 topic={} offset={} error={}",
                            record.topic(), record.offset(), ex.getMessage());
                    return new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition());
                });

        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(15_000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);

        // 确定性错误不重试：JSON 格式错误重试一百次还是格式错误，
        // 只会白白把 15 秒的延迟强加给后面所有消息
        handler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);

        return handler;
    }
}
