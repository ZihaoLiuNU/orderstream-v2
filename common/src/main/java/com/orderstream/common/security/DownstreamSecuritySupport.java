package com.orderstream.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 所有下游（非网关）服务共享的安全基线配置。
 *
 * 【核心设计决策：为什么网关已经鉴权了，下游还要再鉴一次？】
 *
 * 这叫纵深防御(defense in depth)。网关做的是【粗粒度】授权：
 *   「这个 JWT 有效吗」「这条路由需要 ADMIN 吗」
 * 下游做的是【细粒度】授权：
 *   「这个具体的订单是不是这个用户的」
 *
 * 只靠网关的三个致命问题：
 *   1. 网关的路由规则是按 URL 路径写的。哪天有人重构把 /api/orders/{id}/status
 *      改成 /api/orders/{id}/transition，网关的 ADMIN 规则就静默失效了 ——
 *      不会报错，只会悄悄变成人人可调。
 *   2. 网关看不到业务数据，它永远没法判断「订单 42 属于谁」。
 *      对象级授权(BOLA)在架构上就不可能在网关做。
 *   3. 内网不是可信网络。一旦有任何服务被攻破，或者有人误把端口暴露了，
 *      攻击者可以直接带着伪造的 X-User-Role 头打下游。
 *
 * 所以：网关负责「你是谁 + 你这类人能不能碰这个接口」，
 *      服务负责「你能不能碰这条数据」。两层都不能省。
 */
@Configuration
@EnableMethodSecurity   // 开启 @PreAuthorize / @PostAuthorize 方法级注解
public class DownstreamSecuritySupport {

    @Bean
    public IdentityHeaderAuthenticationFilter identityHeaderAuthenticationFilter() {
        return new IdentityHeaderAuthenticationFilter();
    }

    /**
     * 所有下游服务的公共安全设置，各服务在此基础上追加自己的授权规则。
     */
    public static HttpSecurity applyDefaults(HttpSecurity http,
                                             IdentityHeaderAuthenticationFilter identityFilter) throws Exception {
        return http
                // 关掉 CSRF：CSRF 攻击的前提是浏览器会【自动携带】凭证（Cookie）。
                // 我们用的是 Authorization 头 + 无状态会话，浏览器不会自动带，
                // 攻击者的站点也读不到它，所以 CSRF 在这个模型下不成立。
                // 注意：如果哪天把 token 存进 Cookie，就必须把 CSRF 保护加回来
                .csrf(csrf -> csrf.disable())

                // 无状态：服务端不建 HttpSession。
                // 这是水平扩容的前提 —— 任何一个实例都能处理任何一个请求，
                // 不需要 sticky session，也不需要共享 session 存储
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 关掉默认的登录页和 HTTP Basic 弹窗。
                // 这是 REST API，认证失败应该返回 401 JSON，而不是 302 跳转到登录页
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())

                // 把身份头过滤器插在用户名密码认证过滤器之前。
                // 位置很重要：必须在授权决策发生【之前】把 SecurityContext 填好
                .addFilterBefore(identityFilter, UsernamePasswordAuthenticationFilter.class);
    }
}
