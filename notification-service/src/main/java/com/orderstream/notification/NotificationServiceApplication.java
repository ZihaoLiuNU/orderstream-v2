package com.orderstream.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Notification 限界上下文。
 *
 * 【纯消费者：只订阅事件，不产生事件】
 *   它没有 outbox 表，因为它不需要往外发任何领域事件。
 *
 * ============================================================================
 * 【注意下面三个注解只引入了 messaging 的一部分，这里有个真实的坑】
 * ============================================================================
 *
 * 其他服务写的是 "com.orderstream.messaging"（整个包）。
 * 这个服务必须【精确到子包】，只引入 config 和 idempotency，不引入 outbox。
 *
 * 如果偷懒写整个包，启动会直接失败，而且报错信息很有迷惑性：
 *   1) @EntityScan 会扫到 OutboxEvent 这个 @Entity
 *   2) ddl-auto=validate 发现 notification_db 里没有 outbox_events 表
 *   3) 启动报 "Schema-validation: missing table [outbox_events]"
 *
 *   就算改成 ddl-auto=none 绕过校验，OutboxPublisher 和 OutboxRelay
 *   这两个 @Component 也会被创建出来，然后 relay 每 500ms 去查一张
 *   不存在的表，日志被刷爆。
 *
 * 【这件事本身说明了一个设计问题值得注意】：
 *   messaging 是个共享库，但它内部的两块能力（outbox、幂等）
 *   并不是每个服务都需要。
 *   更彻底的做法是拆成 messaging-outbox 和 messaging-idempotency
 *   两个模块，让依赖关系在 pom 层面就表达清楚，
 *   而不是靠使用方"记得只扫某几个子包"。
 *
 *   我保留现在的结构，是因为 7 个服务里有 6 个两者都要，
 *   为 1 个例外拆模块收益不大。但这个取舍是【被想过的】，
 *   而且代价（这条必须写对的注解）在注释里说清楚了。
 */
@SpringBootApplication(scanBasePackages = {
        "com.orderstream.notification",
        "com.orderstream.common",
        "com.orderstream.messaging.config",       // Kafka 生产/消费/topic 配置
        "com.orderstream.messaging.idempotency"   // 幂等消费。刻意不含 outbox
})
@EntityScan(basePackages = {
        "com.orderstream.notification.entity",
        "com.orderstream.messaging.idempotency"   // ProcessedEvent
})
@EnableJpaRepositories(basePackages = {
        "com.orderstream.notification.repository",
        "com.orderstream.messaging.idempotency"
})
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
