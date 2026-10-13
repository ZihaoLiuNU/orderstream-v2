package com.orderstream.menu.config;

import com.orderstream.common.security.DownstreamSecuritySupport;
import com.orderstream.common.security.IdentityHeaderAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           IdentityHeaderAuthenticationFilter identityFilter) throws Exception {
        return DownstreamSecuritySupport.applyDefaults(http, identityFilter)
                .authorizeHttpRequests(auth -> auth
                        // 健康检查必须免认证，否则 Docker/K8s 探针会一直判定服务不健康，
                        // 导致容器被反复重启 —— 这个坑很常见
                        .requestMatchers("/actuator/health/**").permitAll()

                        // 浏览菜单不需要登录：用户应该能先看菜再决定要不要注册。
                        // 注意这里精确到了 HTTP 方法 —— 只放行 GET。
                        // 写成 .requestMatchers("/api/restaurants/**").permitAll()
                        // 就变成任何人都能创建餐厅了，这是白名单最常见的翻车方式
                        .requestMatchers(HttpMethod.GET, "/api/restaurants/**").permitAll()

                        // 内部批量查询接口：要求已认证（网关注入了身份头）。
                        // order-service 调用时会把用户身份透传过来
                        .requestMatchers(HttpMethod.GET, "/api/menu-items").authenticated()

                        // 其余一律要求认证。
                        // 用 anyRequest().authenticated() 做兜底是白名单思路：
                        // 新加的接口默认是受保护的，忘了配只会 401（显眼），
                        // 而不是默默公开（静默的数据泄露）
                        .anyRequest().authenticated())
                .build();
    }
}
