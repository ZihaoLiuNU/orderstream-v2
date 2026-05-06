package com.orderstream.config;

import com.orderstream.event.PaymentRequestEvent;
import com.orderstream.event.PaymentResultEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 消费者工厂配置
 *
 * 每类事件需要独立的 ContainerFactory，以便配置正确的反序列化目标类型。
 * 不同消费者组模拟独立微服务（支付服务、Saga 编排器）。
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // 支付请求消费者工厂（模拟 Payment Service）
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentRequestEvent> paymentKafkaListenerContainerFactory() {
        return buildFactory("payment-service", PaymentRequestEvent.class);
    }

    // Saga 编排器消费者工厂（消费支付结果）
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentResultEvent> sagaKafkaListenerContainerFactory() {
        return buildFactory("saga-orchestrator", PaymentResultEvent.class);
    }

    // 通用工厂方法：为每种事件类型配置专属的 JsonDeserializer
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> buildFactory(
            String groupId, Class<T> targetType) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // 明确指定反序列化目标类型，避免依赖消息头中的类型信息
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(targetType);
        deserializer.addTrustedPackages("com.orderstream.*");

        ConsumerFactory<String, T> factory = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), deserializer);

        ConcurrentKafkaListenerContainerFactory<String, T> container =
                new ConcurrentKafkaListenerContainerFactory<>();
        container.setConsumerFactory(factory);
        return container;
    }
}
