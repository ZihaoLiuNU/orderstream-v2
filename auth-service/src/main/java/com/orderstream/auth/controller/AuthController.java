package com.orderstream.auth.controller;

import com.orderstream.auth.dto.*;
import com.orderstream.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口。这些端点在网关上是【公开】的，不需要 JWT。
 *
 * 注意每个 @RequestBody 都带了 @Valid。
 * 少写一个 @Valid，DTO 上所有的 @NotBlank/@Size 就【完全不生效】，
 * 而且不会有任何警告或报错 —— 校验只是静默地不发生。
 * 这是 Spring Validation 最容易犯的错误，原版代码就是这样。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册成功返回 201 Created 而不是 200 OK。
     * HTTP 语义是 API 契约的一部分：201 明确表示「创建了一个新资源」，
     * 客户端和中间件（缓存、网关、监控）都能据此做正确的处理。
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    /**
     * 登出返回 204 No Content：操作成功，但没有内容要返回。
     * 返回 200 + 空 body 也能用，但 204 更准确。
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
    }
}
