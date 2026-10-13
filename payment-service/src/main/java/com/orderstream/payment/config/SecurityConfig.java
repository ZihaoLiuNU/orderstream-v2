package com.orderstream.payment.config;

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
                        // 支付是全系统最敏感的数据，没有任何公开端点。
                        // 对象级归属校验在 Controller/Service 层
                        .anyRequest().authenticated())
                .build();
    }
}
