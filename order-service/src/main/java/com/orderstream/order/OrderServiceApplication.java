package com.orderstream.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order 限界上下文 —— 整个系统的核心。
 *
 * 它同时扮演两个角色：
 *   1. 订单聚合的所有者（创建、查询、状态流转）
 *   2. 【Saga 编排器】—— 协调 menu-service 和 payment-service 完成分布式事务
 *
 * 这两个角色放在一个服务里是刻意的：
 *   Saga 的状态本质上是"这笔订单进行到哪一步了"，它和订单聚合的生命周期完全一致。
 *   把编排器单独拆成一个服务，会让"订单"和"订单的处理进度"分属两个数据库，
 *   反而制造出一个新的分布式一致性问题 —— 用微服务解决微服务带来的问题，
 *   这是典型的过度拆分。
 *
 *   拆分的标准是【业务能力边界】，不是"代码看起来该不该分开"。
 */
@EnableScheduling   // OutboxRelay + StuckSagaSweeper 需要
@SpringBootApplication(scanBasePackages = {
        "com.orderstream.order",
        "com.orderstream.common",
        "com.orderstream.messaging"
})
@EntityScan(basePackages = {
        "com.orderstream.order.entity",
        "com.orderstream.order.saga",     // OrderSaga 也是 @Entity
        "com.orderstream.messaging"       // OutboxEvent + ProcessedEvent
})
@EnableJpaRepositories(basePackages = {
        "com.orderstream.order.repository",
        "com.orderstream.messaging"
})
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
