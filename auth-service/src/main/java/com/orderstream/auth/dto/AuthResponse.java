package com.orderstream.auth.dto;

import lombok.*;

/**
 * 登录/刷新的统一响应。
 *
 * 返回 expiresInSeconds 而不是一个绝对时间戳，是刻意的：
 * 客户端和服务端的时钟可能不同步（时钟漂移在分布式系统里是常态）。
 * 给相对时长，客户端按自己的时钟算，不受漂移影响。
 * OAuth2 规范用的也是 expires_in 而不是 expires_at，同样的理由。
 */
@Data @AllArgsConstructor @Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;      // 固定 "Bearer"
    private long   expiresInSeconds;
    private Long   userId;
    private String username;
    private String role;
}
