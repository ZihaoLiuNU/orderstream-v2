package com.orderstream.common.web;

/**
 * 已认证但无权访问【这个具体对象】。映射为 HTTP 403。
 *
 * 和 Spring Security 的 AccessDeniedException 的区别很重要：
 *   AccessDeniedException 处理的是【功能级】授权 —— 「你这个角色能不能调用这个接口」。
 *   这个异常处理的是【对象级】授权 —— 「这个接口你能调，但这条数据不是你的」。
 *
 * 后者就是 OWASP API Security Top 10 的第一名 BOLA（Broken Object Level Authorization），
 * 也叫 IDOR。RBAC 只解决前者，对后者完全无能为力 —— 这是最容易踩的安全陷阱，
 * 因为代码看起来「有权限校验」，实际上漏了一半。
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
