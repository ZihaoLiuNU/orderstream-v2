package com.orderstream.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 把网关注入的身份头还原成 Spring Security 的 Authentication 对象。
 *
 * 这样做的好处：下游服务里 @PreAuthorize("hasRole('ADMIN')")、SecurityFilterChain 的
 * 路径规则等 Spring Security 的全套授权能力都能照常用，但完全不碰 JWT。
 * 身份【怎么来的】被封装在这一个类里 —— 将来换成 mTLS 证书或 OAuth2 内省，
 * 也只改这里，业务代码一行不动。
 *
 * 继承 OncePerRequestFilter 而不是直接实现 Filter：
 *   Servlet 规范下，一次请求可能触发多次 filter 调用（forward、include、async dispatch）。
 *   OncePerRequestFilter 用 request attribute 做标记，保证每个请求只执行一次。
 *   如果这里做的是有副作用的事（比如计数、审计），重复执行就是 bug。
 */
@Slf4j
public class IdentityHeaderAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String userId   = request.getHeader(AuthHeaders.USER_ID);
        String username = request.getHeader(AuthHeaders.USERNAME);
        String role     = request.getHeader(AuthHeaders.ROLE);

        // 三个头必须同时存在。缺任何一个都当作匿名请求继续走，
        // 由后面的授权规则决定是放行（公开端点）还是返回 401
        if (userId != null && username != null && role != null) {
            CurrentUser user = new CurrentUser(Long.valueOf(userId), username, role);

            // Spring Security 的约定：hasRole('ADMIN') 实际比对的是权限字符串 "ROLE_ADMIN"。
            // 这个隐式前缀是新手最常踩的坑 —— 存成 "ADMIN" 的话 hasRole 永远返回 false，
            // 而且不会报错，只会静默 403
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

            // credentials 传 null：到这一步身份已经在网关验过了，
            // 下游没有也不需要密码/token
            var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清理。Tomcat 的线程是复用的，如果不清，
            // 下一个请求复用到这个线程时可能读到上一个用户的身份 —— 这是最严重的一类越权 bug。
            // Spring Security 自己的 SecurityContextPersistenceFilter 会做这件事，
            // 但我们这个过滤器在它之前执行，所以自己兜底
            SecurityContextHolder.clearContext();
        }
    }
}
