package com.orderstream.gateway.filter;

import com.orderstream.common.security.AuthHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;

/**
 * 剥离客户端伪造的身份头。
 *
 * ============================================================================
 * 这是整个系统里【最重要的 30 行安全代码】。没有它，全部认证机制形同虚设。
 * ============================================================================
 *
 * 攻击场景：
 *   下游服务信任 X-User-Id / X-Username / X-User-Role 这三个头来确定调用者身份
 *   （见 common 里的 IdentityHeaderAuthenticationFilter）。
 *   如果网关只【添加】这些头而不先【删除】客户端带来的同名头，那么攻击者只需要：
 *
 *     curl http://gateway/api/orders/999 -H "X-User-Id: 1" -H "X-User-Role: ADMIN"
 *
 *   连 token 都不用有，就拿到了管理员权限、能看任何人的订单。
 *   这是完整的认证绕过(authentication bypass)，CVSS 严重等级 Critical。
 *
 * 为什么必须是【独立的过滤器】，而不是写在 JWT 过滤器里：
 *   JWT 过滤器对公开路径(/api/auth/**)会直接放行、提前 return。
 *   如果剥离逻辑写在它里面，公开路径上的伪造头就不会被清掉 ——
 *   攻击者打 /api/auth/xxx 带着伪造头，照样能穿透到下游。
 *   把它拆成一个【无条件执行、优先级最高】的独立过滤器，
 *   就在结构上保证了「任何请求、任何路径、任何情况下都会被剥离」。
 *
 *   安全控制必须是 fail-safe 的：不能依赖「记得在每条分支上都调用一下」，
 *   而要让它在架构上没有旁路可走。
 *
 * 为什么不改成「只在有合法 JWT 时才透传」：
 *   那是同一件事的另一种说法，但实现上更容易出错 ——
 *   只要有一条代码路径忘了覆盖，漏洞就在。先无条件清空，再由认证环节重新写入，
 *   是唯一不会漏的写法。
 */
@Slf4j
@Component
public class IdentityHeaderStripFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();

        // 只有客户端真的带了这些头才需要处理。绝大多数请求走下面的 fast path，
        // 不产生任何对象分配
        boolean spoofAttempt = Arrays.stream(AuthHeaders.CLIENT_SPOOFABLE)
                .anyMatch(h -> request.getHeaders().containsKey(h));

        if (!spoofAttempt) {
            return chain.filter(exchange);
        }

        // 记成 warn 并带上来源 IP：这几乎不可能是正常客户端的行为，
        // 高度疑似主动攻击探测，应该被 SIEM 告警捞出来
        log.warn("[安全] 客户端尝试伪造身份头，已剥离。remoteAddr={} path={}",
                request.getRemoteAddress(), request.getPath());

        var sanitized = request.mutate()
                .headers(headers -> Arrays.stream(AuthHeaders.CLIENT_SPOOFABLE).forEach(headers::remove))
                .build();

        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    /**
     * HIGHEST_PRECEDENCE：在所有其他过滤器之前执行。
     *
     * 这个顺序是安全属性的一部分，不是可以随便调的实现细节。
     * 任何在它之前运行的过滤器看到的都是【未净化】的请求头。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
