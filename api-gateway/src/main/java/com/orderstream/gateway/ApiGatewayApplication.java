package com.orderstream.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关：整个系统唯一对外暴露的入口。
 *
 * 它承担 4 件事，全部是【横切关注点】，不含任何业务逻辑：
 *   1. 路由        —— 按路径把请求转发到对应服务
 *   2. 认证        —— 验证 JWT 签名与有效期，把身份转成 HTTP 头传给下游
 *   3. 防伪造      —— 剥离客户端自带的身份头（见 IdentityHeaderStripFilter，最关键的一环）
 *   4. 限流 + 追踪 —— 令牌桶限流、生成 correlationId
 *
 * 网关【不做】的事（刻意的边界）：
 *   - 不做业务规则判断。「这个订单能不能取消」是 order-service 的事，
 *     网关一旦开始理解业务，就会慢慢长成一个新的单体（这个反模式叫 "smart gateway"）。
 *   - 不做对象级授权。网关看不到数据，判断不了「订单 42 是不是你的」。
 *   - 不连数据库。任何一次查库都会让网关变成整个系统的性能瓶颈和单点故障。
 */
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
