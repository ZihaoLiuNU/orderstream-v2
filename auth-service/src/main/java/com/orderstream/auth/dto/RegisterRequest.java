package com.orderstream.auth.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 注册请求。
 *
 * 每个字段都有校验注解，配合 Controller 上的 @Valid 生效。
 * 原版代码完全没有校验 —— 那意味着 null 用户名会一路走到数据库才炸成 500，
 * 而不是在入口处返回一个清晰的 400。
 *
 * 一条通用原则：校验要在【系统边界】做，越早越好。
 * 让非法数据进入系统内部，之后每一层都要防御性地重新判断一遍。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度必须在 3 到 50 之间")
    // 限制字符集不只是为了整洁：用户名会出现在日志、URL、错误信息里，
    // 放开任意字符等于给日志注入和 XSS 留后门
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 255)
    private String email;

    @NotBlank(message = "密码不能为空")
    // 下限 8 位是最低要求。上限 72 是 BCrypt 的硬限制：
    // BCrypt 只处理前 72 字节，超出部分【被静默忽略】。
    // 不设上限的话，用户设了 100 位密码却只有前 72 位生效，这是个真实存在的安全陷阱
    @Size(min = 8, max = 72, message = "密码长度必须在 8 到 72 之间")
    private String password;
}
