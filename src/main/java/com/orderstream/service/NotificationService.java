package com.orderstream.service;

import com.orderstream.event.OrderStatusChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通知服务
 * 当前实现为日志模拟；真实生产环境可替换为 AWS SNS、Firebase FCM 或邮件服务
 */
@Slf4j
@Service
public class NotificationService {

    public void sendOrderStatusNotification(OrderStatusChangedEvent event) {
        String message = buildMessage(event);
        // 模拟向用户推送通知（生产环境对接短信/推送平台）
        log.info("[推送通知] → 用户 {}: {}", event.getUserId(), message);
    }

    private String buildMessage(OrderStatusChangedEvent event) {
        return switch (event.getNewStatus()) {
            case CONFIRMED -> "您的订单 #" + event.getOrderId() + " 已确认，餐厅正在备餐";
            case PREPARING -> "餐厅已开始制作您的订单 #" + event.getOrderId();
            case DELIVERED -> "订单 #" + event.getOrderId() + " 已送达，感谢您的惠顾！";
            case CANCELLED -> "订单 #" + event.getOrderId() + " 已取消";
            default -> "您的订单 #" + event.getOrderId() + " 状态已更新";
        };
    }
}
