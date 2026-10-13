package com.orderstream.common.web;

/** 业务规则被违反（非法状态流转、库存不足等）。映射为 HTTP 400。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
