package com.orderstream.cart.config;

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
                        // 购物车是用户私有数据，无公开端点
                        .anyRequest().authenticated())
                .build();
    }
}
