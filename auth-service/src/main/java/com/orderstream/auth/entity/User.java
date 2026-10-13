package com.orderstream.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    /**
     * BCrypt 哈希。
     * 注意这个字段绝不能出现在任何返回给客户端的 DTO 里 ——
     * 这也是我坚持用独立 DTO 而不是直接返回 Entity 的原因之一：
     * 直接返回 Entity 的话，将来给 User 加一个敏感字段，它会自动出现在 API 响应里，
     * 没有任何东西会提醒你。
     */
    @Column(nullable = false, length = 60)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum Role { USER, ADMIN }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
