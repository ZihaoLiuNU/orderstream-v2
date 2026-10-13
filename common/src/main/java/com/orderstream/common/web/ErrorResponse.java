package com.orderstream.common.web;

import lombok.*;
import java.time.Instant;
import java.util.List;

/**
 * 统一错误响应体。
 *
 * 放在 common 是因为它是【对外 API 契约的一部分】：
 * 客户端要能用同一套代码解析 7 个服务返回的错误，格式必须一致。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ErrorResponse {
    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    /** 字段级校验错误明细，例如 "quantity: 必须大于 0" */
    private List<String> details;
    /** 全链路追踪 ID，用户报障时报这个号，我们就能捞到完整调用链 */
    private String correlationId;
}
