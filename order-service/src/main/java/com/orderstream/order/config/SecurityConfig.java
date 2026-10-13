package com.orderstream.order.config;

import com.orderstream.common.security.DownstreamSecuritySupport;
import com.orderstream.common.security.IdentityHeaderAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           IdentityHeaderAuthenticationFilter identityFilter) throws Exception {
        return DownstreamSecuritySupport.applyDefaults(http, identityFilter)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        // 订单服务【没有任何公开端点】。
                        // 订单是用户私有数据，连列表都不该匿名可见。
                        // 细粒度的角色控制在 Controller 的 @PreAuthorize 上，
                        // 对象级归属控制在 Service 层（OrderService.loadOwnedOrder）
                        .anyRequest().authenticated())
                .build();
    }
}
