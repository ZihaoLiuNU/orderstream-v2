package com.orderstream.gateway.filter;

import com.orderstream.common.security.AuthHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 给每个请求打上唯一的 correlationId，并透传到所有下游服务和 Kafka 事件。
 *
 * 为什么微服务里这东西是刚需：
 *   一次下单会依次经过 gateway -> order-service -> menu-service（同步）
 *   -> Kafka -> payment-service -> Kafka -> order-service -> Kafka -> notification-service。
 *   7 个服务、3 次异步跳转。用户报「我刚才下单失败了」，
 *   没有 correlationId 的话，你只能按时间戳在 7 份日志里大海捞针，
 *   而高并发下同一秒有上百个请求，根本分不清哪条日志属于哪次调用。
 *
 *   有了它，一句 grep 就能拿到完整链路。这是微服务可运维性的最低门槛 ——
 *   比监控大盘还基础。
 *
 * 为什么复用客户端传来的 ID（如果有）：
 *   移动端 App 也可以生成自己的 requestId 并带上来。这样从「用户点击按钮」
 *   到「后端 7 个服务」的整条链路是同一个 ID，客户端日志和服务端日志能对上。
 *   这是完整可观测性链路的最后一公里。
 *
 * 和 OpenTelemetry 的关系：
 *   生产环境应该直接上 OTel/W3C Trace Context（traceparent 头），
 *   它还能自动记录每一段的耗时，直接画出火焰图。
 *   这里手写一个是为了把原理讲清楚 —— OTel 做的本质上就是这件事，
 *   只是多了采样、span 层级和自动埋点。
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(AuthHeaders.CORRELATION_ID);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String cid = correlationId;

        var request = exchange.getRequest().mutate()
                .header(AuthHeaders.CORRELATION_ID, cid)
                .build();

        // 同时写回响应头：用户报障时可以直接从浏览器开发者工具里读到这个 ID 报给客服
        exchange.getResponse().getHeaders().add(AuthHeaders.CORRELATION_ID, cid);

        return chain.filter(exchange.mutate().request(request).build());
    }

    /**
     * 排在剥离过滤器【之后】、认证过滤器【之前】。
     *
     * 为什么不能排第一：correlationId 是客户端可控的输入，
     * 而剥离过滤器的职责就是处理客户端可控输入。保持「先净化、后加工」的顺序。
     * 为什么要在认证之前：认证失败的日志也需要带 correlationId，
     * 否则排查「为什么这个用户一直 401」时就断链了。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
