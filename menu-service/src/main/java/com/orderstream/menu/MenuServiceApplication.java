package com.orderstream.menu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Menu 限界上下文：菜品目录 + 库存。
 *
 * 它在 Saga 中扮演【参与者】角色（步骤 1：预留库存 / 补偿：释放库存）。
 *
 * 【关于 scanBasePackages / EntityScan / EnableJpaRepositories 三件套】
 *   Spring Boot 默认只扫描启动类所在包及其子包。
 *   我们的共享代码在 com.orderstream.common 和 com.orderstream.messaging 下，
 *   不是 com.orderstream.menu 的子包，所以必须显式声明。
 *
 *   而且【三者都要单独配】，这是个容易漏的点：
 *     scanBasePackages      -> 扫 @Component/@Service/@Configuration
 *     @EntityScan           -> 扫 @Entity（OutboxEvent、ProcessedEvent）
 *     @EnableJpaRepositories -> 扫 Repository 接口
 *   只配第一个的话，启动时会报 "Not a managed type: OutboxEvent"，
 *   很多人会误以为是依赖没引入。
 */
@EnableScheduling   // OutboxRelay 和 StaleReservationSweeper 需要
@SpringBootApplication(scanBasePackages = {
        "com.orderstream.menu",
        "com.orderstream.common",
        "com.orderstream.messaging"
})
@EntityScan(basePackages = {
        "com.orderstream.menu.entity",
        "com.orderstream.messaging"     // OutboxEvent + ProcessedEvent
})
@EnableJpaRepositories(basePackages = {
        "com.orderstream.menu.repository",
        "com.orderstream.messaging"
})
public class MenuServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MenuServiceApplication.class, args);
    }
}
