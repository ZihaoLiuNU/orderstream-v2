package com.orderstream.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment 限界上下文。
 *
 * 在 Saga 中扮演【参与者】角色：
 *   步骤 2：扣款
 *   补偿 2：退款
 *
 * 它完全不知道 Saga 的全貌 —— 不知道前面有库存预留，
 * 也不知道自己失败之后会发生什么。这种无知是刻意的：
 * 参与者一旦开始理解全局流程，编排式就退化成了分布式的意大利面条。
 */
@EnableScheduling   // OutboxRelay 需要
@SpringBootApplication(scanBasePackages = {
        "com.orderstream.payment",
        "com.orderstream.common",
        "com.orderstream.messaging"
})
@EntityScan(basePackages = {
        "com.orderstream.payment.entity",
        "com.orderstream.messaging"
})
@EnableJpaRepositories(basePackages = {
        "com.orderstream.payment.repository",
        "com.orderstream.messaging"
})
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
