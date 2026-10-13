package com.orderstream.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 系统里【唯一】签发 access token 的地方。
 *
 * 密钥管理：
 *   用的是 HS256（对称签名）—— 签名和验签是同一把密钥。
 *   这意味着网关必须持有和 auth-service 一样的密钥。
 *
 *   这是个真实的架构权衡：
 *     对称(HS256)：简单，但每个需要【验签】的服务都持有了【签发】的能力。
 *                  网关被攻破 = 攻击者可以伪造任意用户的 token。
 *     非对称(RS256/ES256)：auth-service 持私钥签发，其他服务只拿公钥验签。
 *                  公钥泄露无所谓，配合 JWKS 端点还能做无缝密钥轮换。
 *
 *   生产环境应该用 RS256 + JWKS。这里用 HS256 是为了控制复杂度，
 *   前提是只有网关和 auth-service 两个组件持有密钥，且都在私有网络里。
 */
@Slf4j
@Component
public class JwtIssuer {

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtIssuer(@Value("${jwt.secret}") String secret,
                     @Value("${jwt.access-token-ttl}") Duration accessTokenTtl) {

        // 显式指定 UTF-8。用 secret.getBytes() 会走平台默认字符集 ——
        // 开发机是 UTF-8、服务器是 GBK 的话，同一个密钥字符串会产生不同的字节数组，
        // 结果就是「本地能验过、线上全部 401」这种极难排查的问题
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        // HS256 要求密钥至少 256 bit。jjwt 会在密钥太短时直接抛异常 ——
        // 这是好事：弱密钥意味着签名可被暴力破解，静默降级比直接失败危险得多
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "jwt.secret 至少需要 32 字节(256 bit)，当前只有 " + keyBytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenTtl = accessTokenTtl;
    }

    /**
     * 签发 access token。
     *
     * 把 uid 和 role 放进 claim 是个关键决策：
     *   这样网关验签后就能直接拿到身份和角色，【不需要查数据库】。
     *   原版代码的 token 里只有 username，导致每个请求都要 loadUserByUsername 查一次库 ——
     *   在 500 并发下就是 500 次额外查询在抢连接池，认证本身成了瓶颈。
     *
     * 代价：role 被「冻结」在 token 里了。管理员把某用户降权后，
     *   那个用户手上的 token 在剩余有效期内依然是管理员。
     *   这正是 access token TTL 必须短(15 分钟)的原因 —— 用时间窗口限制损失，
     *   而不是用查库来保证实时性。这是「无状态」的固有代价，不是 bug。
     */
    public String issue(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }
}
