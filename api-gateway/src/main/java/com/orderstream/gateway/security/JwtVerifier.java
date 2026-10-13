package com.orderstream.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * JWT 验签。注意这里【只验证，不签发】。
 *
 * 全程不查数据库，这是网关能扛高并发的前提：
 *   JWT 是自包含的 —— 签名验过，就说明里面的 uid/role 确实是 auth-service 签的，
 *   没有被篡改。不需要再去数据库确认「这个用户存在吗」。
 *   原版代码每个请求都 loadUserByUsername 查一次库，在 500 并发下
 *   就是 500 次额外查询在抢连接池，认证本身成了瓶颈。
 */
@Slf4j
@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("jwt.secret 至少需要 32 字节(256 bit)");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 验签并解析。
     *
     * 只解析一次是刻意的：原版代码先调 isTokenValid() 再调 extractUsername()，
     * 等于对每个请求做了【两次】HMAC 签名验证和 JSON 解析。
     * 在网关这种每个请求都要过的热路径上，这种重复开销会直接体现在 p99 上。
     *
     * 返回 Optional 而不是抛异常：token 无效是【预期内】的正常情况
     * （过期了、伪造的、格式错的），不是异常情况。
     * 用异常做正常控制流既慢（要填充 stack trace）又难读。
     */
    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    // parseSignedClaims 会同时校验签名和 exp 过期时间，
                    // 过期会抛 ExpiredJwtException（是 JwtException 的子类）
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new AuthenticatedUser(
                    claims.get("uid", Long.class),
                    claims.getSubject(),
                    claims.get("role", String.class)
            ));

        } catch (JwtException | IllegalArgumentException e) {
            // debug 级别：无效 token 是高频事件（token 过期很常见），
            // 用 warn/error 会把日志刷爆，真正的安全事件反而被淹没
            log.debug("JWT 验证失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 从 token 中解析出的身份 */
    public record AuthenticatedUser(Long userId, String username, String role) {}
}
