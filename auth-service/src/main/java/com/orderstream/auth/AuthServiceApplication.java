package com.orderstream.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 认证服务：系统里唯一签发 JWT 的地方，也是唯一存储用户凭据的地方。
 *
 * 它【不是】一个业务限界上下文 —— 系统的 5 个 bounded context
 * (Menu / Cart / Order / Payment / Notification) 都是业务能力。
 * 认证是横切的基础设施能力，和网关一样。
 * 这个区分在 DDD 里很重要：限界上下文是按【业务语言的边界】划的，
 * 不是按「我拆了几个服务」划的。
 *
 * 注意 scanBasePackages 没有包含 com.orderstream.common.security：
 * auth-service 是【签发】token 的，不【消费】网关注入的身份头，
 * 所以它不需要 IdentityHeaderAuthenticationFilter。
 * 它只需要 common 里的异常处理。
 */
@EnableScheduling   // 开启 @Scheduled 支持（RefreshTokenCleanupTask）
@SpringBootApplication(scanBasePackages = {
        "com.orderstream.auth",
        "com.orderstream.common.web"
})
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
