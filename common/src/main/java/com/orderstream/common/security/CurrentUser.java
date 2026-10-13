package com.orderstream.common.security;

import lombok.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前请求的调用者身份。
 *
 * 身份的来源是网关注入的 HTTP 头，不是 JWT —— 下游服务从不解析 JWT。
 * 见 AuthHeaders 里对这个设计的完整说明。
 */
@Data @AllArgsConstructor
public class CurrentUser {

    private final Long userId;
    private final String username;
    private final String role;   // USER 或 ADMIN（不带 ROLE_ 前缀）

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /**
     * 从 SecurityContext 取出当前用户。
     *
     * 为什么不用 @AuthenticationPrincipal 注入到 Controller 方法参数：
     *   两种都行。但 Service 层也需要知道当前用户（对象级授权检查在 Service 层做，
     *   见 OrderService），Service 拿不到 Controller 的方法参数，
     *   所以统一走 SecurityContextHolder 更一致。
     *
     * SecurityContextHolder 默认用 ThreadLocal 存储 —— 注意这意味着：
     *   把工作丢到另一个线程（@Async、CompletableFuture.supplyAsync）时，
     *   新线程里是取不到当前用户的。这是个很隐蔽的坑，见 order-service 里
     *   并行调用 menu-service 的地方，我是先取出身份再进线程池的。
     */
    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            throw new IllegalStateException(
                "SecurityContext 中没有身份信息。说明请求没有经过网关，或 IdentityHeaderAuthenticationFilter 未生效");
        }
        return user;
    }
}
