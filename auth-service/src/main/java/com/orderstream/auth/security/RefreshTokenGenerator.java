package com.orderstream.auth.security;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh token 的生成与哈希。
 */
@Component
public class RefreshTokenGenerator {

    /**
     * SecureRandom 而不是 Random。
     *
     * java.util.Random 用的是线性同余算法，种子只有 48 bit，
     * 观察到几个输出就能反推出内部状态、预测所有后续输出。
     * 用它生成 token 等于把所有人的账号拱手送人。
     * 任何【安全相关】的随机数必须用 SecureRandom（底层是操作系统的 CSPRNG）。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 字节 = 256 bit 熵，暴力穷举在物理上不可行 */
    private static final int TOKEN_BYTES = 32;

    /** 生成原始 token（只在这一刻存在于内存和响应体里，之后服务端只保留哈希） */
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe 编码：token 可能出现在 URL 或 header 里，标准 Base64 的 +/ 会引发转义问题
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 存进数据库的是这个哈希。
     *
     * 为什么这里用 SHA-256 而不是 BCrypt（存密码时我们用的是 BCrypt）：
     *   BCrypt 是【故意设计得很慢】的，用来对抗离线暴力破解 ——
     *   因为用户密码熵很低（"password123" 这种），慢才能让穷举不可行。
     *   而 refresh token 是我们自己生成的 256 bit 随机串，熵已经拉满，
     *   根本不存在「猜」的可能，不需要慢哈希。
     *   如果这里用 BCrypt，每次刷新都要几百毫秒 CPU，还【没法建索引查找】
     *   （BCrypt 每次哈希结果都不同，只能逐行比对）。
     *   所以：低熵秘密(密码)用慢哈希，高熵秘密(token)用快哈希。
     */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);   // 64 个十六进制字符
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制要求实现的算法，这个分支实际不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
