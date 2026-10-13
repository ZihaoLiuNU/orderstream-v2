package com.orderstream.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token 的持久化记录。
 *
 * 为什么 refresh token 是【不透明随机串】而不是 JWT：
 *   JWT 的全部意义是「自包含、无需查库即可验证」。但 refresh token 的核心需求恰恰是
 *   【可撤销】—— 可撤销就必然要查存储。那把它做成 JWT 就只剩下坏处：
 *   体积大、包含可被解码的信息、还得防重放。
 *   所以：access token 用 JWT（短效、无状态、不可撤销但反正很快过期），
 *        refresh token 用随机串（长效、有状态、可撤销）。
 *   这个「短效无状态 + 长效有状态」的组合是 OAuth2 的标准做法。
 */
@Entity
@Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256(原始 token) 的十六进制串，64 字符 */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 同一次登录派生出的所有 token 共享，用于重放检测时整族吊销 */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 非空表示已吊销。用软删除而非物理删除，因为「这个 token 被用过」这个事实是重放检测的依据 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
