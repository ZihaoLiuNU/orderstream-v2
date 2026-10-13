package com.orderstream.gateway.config;

import com.orderstream.common.security.AuthHeaders;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 限流的「按什么维度限」。
 *
 * Spring Cloud Gateway 的 RequestRateLimiter 用的是 Redis + Lua 实现的令牌桶：
 *   - 放在 Redis 而不是各实例内存里，是因为限流状态必须【跨实例共享】。
 *     如果每个网关实例各限各的，扩容到 3 个实例就等于把阈值放大了 3 倍，
 *     限流形同虚设。
 *   - 用 Lua 脚本是为了原子性：「读当前令牌数 - 判断 - 扣减」这三步必须原子完成，
 *     否则并发下会超发。Redis 保证单个 Lua 脚本的执行是原子的。
 */
@Configuration
public class RateLimiterConfig {

    /**
     * 默认维度：已登录用户按 userId 限，未登录按 IP 限。
     *
     * 为什么优先用 userId 而不是一律用 IP：
     *   同一个 IP 背后可能是整栋写字楼、整个学校的 NAT 出口，
     *   按 IP 限流会让一个人的异常行为连累几百个正常用户。
     *   已登录用户有稳定身份，按 userId 限流才精确。
     *
     * 为什么未登录还得按 IP：
     *   注册/登录接口本来就没有 userId，而这两个接口恰恰是最需要限流的
     *   （撞库、爆破、批量注册垃圾账号都打这里）。
     *   按 IP 限是唯一可用的维度，虽然粗糙。
     *
     * 注意 X-User-Id 头在这里是【可信】的：
     *   它已经被 IdentityHeaderStripFilter 剥离过客户端伪造值，
     *   又被 JwtAuthenticationFilter 用验过签的身份重新写入。
     *   两个过滤器的 order 都小于路由过滤器，所以执行顺序有保证。
     *   如果顺序搞反了，攻击者随便编一个 X-User-Id 就能绕过限流。
     */
    @Bean
    @Primary
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID);
            if (userId != null) {
                return Mono.just("user:" + userId);
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            String ip = (remote != null && remote.getAddress() != null)
                    ? remote.getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }

    /**
     * 认证接口专用：强制按 IP 限流。
     *
     * 为什么不能用上面那个：
     *   登录接口的攻击模式是「一个攻击者尝试一万个不同账号」。
     *   如果按 userId 限，攻击者每次换一个用户名，每个 userId 的计数都是 1，
     *   限流完全不会触发。必须按来源 IP 限，才能拦住爆破。
     *
     *   这就是限流维度必须【匹配威胁模型】的例子：
     *   同一个限流组件，维度选错了就等于没有。
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            String ip = (remote != null && remote.getAddress() != null)
                    ? remote.getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("authip:" + ip);
        };
    }
}
