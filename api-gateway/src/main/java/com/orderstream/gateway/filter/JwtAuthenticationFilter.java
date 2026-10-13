package com.orderstream.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderstream.common.security.AuthHeaders;
import com.orderstream.common.web.ErrorResponse;
import com.orderstream.gateway.security.JwtVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * 边缘认证：验证 JWT，把身份转成 HTTP 头交给下游。
 *
 * 这是「认证」(Authentication，你是谁)，不是「授权」(Authorization，你能做什么)。
 * 授权分两层，都不在这里：
 *   - 功能级授权：各服务的 SecurityFilterChain / @PreAuthorize
 *   - 对象级授权：各服务的 Service 层（见 order-service 的所有权校验）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 免认证路径白名单。
     *
     * 【用白名单而不是黑名单】是安全设计的基本原则：
     *   黑名单（「除了这些路径外都不需要认证」）的失效模式是【默认放行】——
     *   新加一个接口忘了加进黑名单，它就是公开的，而且没有任何提示。
     *   白名单的失效模式是【默认拒绝】—— 新接口忘了配就是 401，
     *   会立刻被发现并修复。
     *
     *   两种失效模式的代价完全不对称：一个是静默的数据泄露，一个是显眼的报错。
     *   永远选择「出错时朝安全的方向倒」(fail secure)。
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/actuator/health/**",
            // 菜单浏览不需要登录：用户应该能先看菜再决定注册。
            // 注意只开放 GET，写操作仍然需要认证（见下面的 isPublic 判断）
            "/api/restaurants/**"
    );

    @Value("${orderstream.gateway.admin-only-paths:}")
    private List<String> adminOnlyPathPatterns = List.of();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (isPublic(request)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "缺少或格式错误的 Authorization 头");
        }

        var userOpt = jwtVerifier.verify(authHeader.substring(7));
        if (userOpt.isEmpty()) {
            // 401 而不是 403：401 = 「我不知道你是谁」（换个有效 token 再来）
            //                  403 = 「我知道你是谁，但你没权限」（换 token 也没用）
            // 原版代码在 token 无效时是直接放行给 Spring Security，最终返回 403，
            // 这会误导客户端去排查权限问题，而实际上只是 token 过期了需要刷新
            return reject(exchange, HttpStatus.UNAUTHORIZED, "token 无效或已过期");
        }

        var user = userOpt.get();

        // 粗粒度的功能级授权：某些路径只有 ADMIN 能碰。
        // 下游服务会【再校验一次】—— 见 DownstreamSecuritySupport 里关于纵深防御的说明
        if (isAdminOnly(path) && !"ADMIN".equals(user.role())) {
            log.warn("[安全] 非管理员访问管理端点 userId={} path={}", user.userId(), path);
            return reject(exchange, HttpStatus.FORBIDDEN, "需要管理员权限");
        }

        // 身份注入。此时客户端自带的同名头已经被 IdentityHeaderStripFilter 清掉了，
        // 所以这里写入的一定是经过验签的可信身份
        var authenticated = request.mutate()
                .header(AuthHeaders.USER_ID,  String.valueOf(user.userId()))
                .header(AuthHeaders.USERNAME, user.username())
                .header(AuthHeaders.ROLE,     user.role())
                .build();

        return chain.filter(exchange.mutate().request(authenticated).build());
    }

    private boolean isPublic(ServerHttpRequest request) {
        String path = request.getPath().value();

        // 餐厅/菜单只对 GET 公开。
        // 如果不加这个方法判断，POST /api/restaurants 就变成了任何人都能创建餐厅 ——
        // 这是路径白名单最常见的翻车方式：只看路径不看方法
        if (PATH_MATCHER.match("/api/restaurants/**", path)) {
            return HttpMethod.GET.equals(request.getMethod());
        }

        return PUBLIC_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private boolean isAdminOnly(String path) {
        return adminOnlyPathPatterns.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    /**
     * 直接在网关终止请求并返回统一格式的错误。
     *
     * 格式和下游服务的 GlobalExceptionHandler 完全一致（都用 ErrorResponse），
     * 这样客户端不需要区分「错误来自网关」还是「错误来自某个服务」。
     * API 的一致性是契约的一部分。
     */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        var body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .correlationId(exchange.getRequest().getHeaders().getFirst(AuthHeaders.CORRELATION_ID))
                .build();

        try {
            DataBuffer buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(body));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            // 序列化都失败的话，至少保证状态码是对的，不要把异常再抛出去
            return response.setComplete();
        }
    }

    /** 在剥离和 correlationId 之后执行 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
