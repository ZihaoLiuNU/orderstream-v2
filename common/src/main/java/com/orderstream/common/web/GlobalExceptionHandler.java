package com.orderstream.common.web;

import com.orderstream.common.security.AuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * 全局异常处理（所有 MVC 服务共享）。
 *
 * 为什么要有它：
 *   没有它的话，未捕获异常会走 Spring Boot 默认的 /error，返回一个带 stack trace 的
 *   HTML 或 JSON。这既不符合 API 契约，又会把内部类名、SQL 片段泄露给攻击者 ——
 *   这叫信息泄露（information disclosure），是渗透测试第一步就会找的东西。
 *
 * 所以最后那个兜底的 handleUnexpected 是刻意设计的：
 *   日志里记完整异常（我们要排障），响应体里只给一句通用文案 + correlationId
 *   （用户报障时报这个号，我们去日志里捞）。绝不把 ex.getMessage() 直接返给客户端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null, req);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null, req);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        // 注意日志级别是 warn：对象级越权尝试是安全信号，应该能被告警规则捞出来
        log.warn("[安全] 对象级授权拒绝: {} uri={}", ex.getMessage(), req.getRequestURI());
        return build(HttpStatus.FORBIDDEN, "无权访问该资源", null, req);
    }

    /**
     * @Valid 校验失败。
     * 这里把每个字段的错误都列出来，因为字段级校验错误对客户端是【可操作的】信息，
     * 告诉用户「quantity 必须大于 0」不会泄露任何内部实现。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "请求参数校验失败", details, req);
    }

    /** 兜底：任何没被上面接住的异常。绝不泄露内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        String correlationId = req.getHeader(AuthHeaders.CORRELATION_ID);
        log.error("[未预期异常] correlationId={} uri={}", correlationId, req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试", null, req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                List<String> details, HttpServletRequest req) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .details(details)
                .correlationId(req.getHeader(AuthHeaders.CORRELATION_ID))
                .build());
    }
}
