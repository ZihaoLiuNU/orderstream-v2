package com.orderstream.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * auth-service 的安全配置。
 *
 * 它和其他下游服务【不一样】，所以没有复用 DownstreamSecuritySupport：
 *   其他服务消费网关注入的身份头；auth-service 是身份的【源头】，
 *   调用它的请求本来就还没有身份。
 *
 * 所以这里所有端点都 permitAll —— 授权在业务逻辑里（密码校验）而不是在过滤器链里。
 * 安全性靠三点保证：
 *   1. 容器不暴露端口，只有网关能访问（docker-compose 里没有 ports 映射）
 *   2. 网关对 /api/auth/** 做限流，防爆破
 *   3. 业务逻辑本身就是认证逻辑
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * BCrypt 强度参数（cost factor）。
     *
     * 10 是 Spring Security 的默认值，意味着 2^10 = 1024 轮迭代，约 100ms。
     * 这个数字必须随硬件发展而提高 —— BCrypt 的设计初衷就是「可调慢」，
     * 让攻击者拿到哈希库后的离线爆破成本保持在不可行的量级。
     *
     * 选值的权衡：
     *   太低 -> 爆破容易
     *   太高 -> 登录接口自己成了 DoS 靶子（每次登录烧几百 ms CPU，
     *          攻击者只要并发发登录请求就能打满你的 CPU）
     * 业界经验法则是把单次哈希控制在 100-250ms。12 在现代服务器上约 250ms，
     * 这里选 12 是因为登录不是高频操作，可以用更高的安全边际。
     *
     * 注意 BCrypt 哈希串里自带 cost factor（$2a$12$... 里的 12），
     * 所以提高 cost 后，老用户的旧哈希依然能正常校验 ——
     * 可以在用户下次登录成功时透明地用新 cost 重新哈希。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
