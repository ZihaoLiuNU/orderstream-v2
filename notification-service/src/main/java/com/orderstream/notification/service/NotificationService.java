package com.orderstream.notification.service;

import com.orderstream.common.event.OrderStatusChangedEvent;
import com.orderstream.notification.entity.Notification;
import com.orderstream.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 订单状态变更 -> 用户通知。
 *
 * ============================================================================
 * 【这个服务存在的意义：把"非关键路径"从主流程里摘出去】
 * ============================================================================
 *
 * 如果发通知是在 order-service 里同步做的：
 *   - 短信网关慢 500ms，用户的下单接口就慢 500ms
 *   - 短信网关挂了，下单接口跟着报错 —— 用户因为"短信发不出去"而下不了单
 *
 * 这显然是荒谬的：通知失败【不应该】影响订单本身。
 *
 * 通过 Kafka 解耦之后：
 *   - order-service 只管把状态变更事件发出去，不关心谁在听、听没听到
 *   - notification-service 慢或者挂，订单流程完全不受影响，
 *     消息在 Kafka 里堆着，恢复后自动补上
 *
 * 这就是"用 Kafka 解耦下游服务"的真正含义 ——
 * 不是"用了消息队列"这个技术事实，而是【故障隔离】这个架构效果：
 * 非关键路径的故障不会传播到关键路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /**
     * 处理订单状态变更事件。
     *
     * 【为什么这个消费者【必须】幂等，而且必须用 ProcessedEvent 表】
     *   发通知是【有外部副作用】的操作 —— 一旦短信发出去就收不回来了。
     *   重复消费 = 用户收到两条一模一样的"您的订单已确认"。
     *
     *   注意它和"更新订单状态"的区别：
     *     setStatus(CONFIRMED) 执行两次和一次结果相同（赋值型，天然幂等）
     *     sendSms() 执行两次就是发了两条（累加型，绝不幂等）
     *
     *   判断标准就是 f(f(x)) == f(x) 是否成立。
     *   这个判断必须【逐个操作】做，不能一刀切说"我们的消费者都幂等"。
     */
    @Transactional
    public void handleOrderStatusChanged(OrderStatusChangedEvent event) {

        String body = buildMessage(event);
        if (body == null) {
            // 不是所有状态变更都值得打扰用户。
            // PENDING -> PENDING 之类的内部流转对用户没有意义。
            //
            // 【通知设计的一条原则：不要为每个内部状态都发一条通知】
            // 通知疲劳(notification fatigue)会导致用户关掉推送权限，
            // 那么真正重要的通知（订单被取消、需要重新支付）也送不到了。
            // 少而准，胜过多而杂。
            log.debug("[通知] 状态 {} 无需通知用户 orderId={}", event.getNewStatus(), event.getOrderId());
            return;
        }

        Notification notification = Notification.builder()
                .userId(event.getUserId())
                .orderId(event.getOrderId())
                .channel(Notification.Channel.PUSH)
                .title("订单状态更新")
                .body(body)
                .status(Notification.Status.PENDING)
                .build();

        /*
         * 【先落库(PENDING)，再发送，最后更新状态】
         *
         * 为什么不是"发送成功了再落库"：
         *   那样的话，"已经发出去了但还没来得及落库"时崩溃，
         *   记录就永久丢失了 —— 用户收到了通知，但系统里查不到发过。
         *   客服完全无法排查。
         *
         * 先落 PENDING 的好处：
         *   任何时候崩溃，最坏情况是留下一条 PENDING 记录（可以被重试任务捡起来），
         *   而不是"什么痕迹都没有"。
         *
         * 这是 write-ahead（预写）思想 ——
         * 和数据库的 WAL、Outbox 模式是同一个道理：
         * 【先记录意图，再执行动作】，这样崩溃后才有恢复的依据。
         */
        notificationRepository.save(notification);

        try {
            dispatch(notification);
            notification.setStatus(Notification.Status.SENT);
            notification.setSentAt(Instant.now());

        } catch (Exception e) {
            /*
             * 【发送失败时吞掉异常，只标记状态 —— 这是刻意的】
             *
             * 如果抛出去，Kafka 会重试、重试耗尽后进死信队列。
             * 但通知失败【不值得】走那么重的流程：
             *   - 它不影响任何业务正确性
             *   - 失败记录已经落库了，定时重试任务会处理
             *
             * 更重要的是：让一条通知的失败去占用 Kafka 的重试和死信机制，
             * 会淹没真正需要关注的消息（比如支付结果处理失败）。
             *
             * 【什么时候该抛、什么时候该吞，取决于失败的严重性】：
             *   影响数据正确性 -> 必须抛，让事务回滚 + 重投
             *   只影响用户体验 -> 记录下来，异步补偿
             */
            notification.setStatus(Notification.Status.FAILED);
            notification.setFailureReason(truncate(e.getMessage()));
            log.warn("[通知] 发送失败，已记录待重试 orderId={} userId={} error={}",
                    event.getOrderId(), event.getUserId(), e.getMessage());
        }

        notificationRepository.save(notification);
    }

    /**
     * 实际的推送动作。
     *
     * 当前是日志模拟。生产环境会替换成 AWS SNS / Firebase FCM / Twilio。
     *
     * 【把它单独抽成一个方法是有意的】：
     *   替换实现时只改这一个地方，上面的"落库-发送-更新状态"骨架完全不动。
     *   更好的做法是把它抽成一个 NotificationSender 接口，
     *   不同渠道(PUSH/SMS/EMAIL)各一个实现，用策略模式分发。
     *   这里没做是因为只有一个渠道 —— 在只有一个实现时引入接口是
     *   过早抽象(premature abstraction)，徒增阅读成本。
     *   等真的有第二个渠道时再抽，那时需求也更清楚了。
     */
    private void dispatch(Notification notification) {
        log.info("[推送] -> 用户 {} : {}", notification.getUserId(), notification.getBody());
    }

    /**
     * 根据状态生成通知文案。返回 null 表示这个状态不需要通知用户。
     */
    private String buildMessage(OrderStatusChangedEvent event) {
        return switch (event.getNewStatus()) {
            case "CONFIRMED" -> "您的订单 #" + event.getOrderId() + " 已确认，餐厅正在备餐";
            case "PREPARING" -> "餐厅已开始制作您的订单 #" + event.getOrderId();
            case "DELIVERED"  -> "订单 #" + event.getOrderId() + " 已送达，感谢您的惠顾";
            // 取消通知必须带上原因 —— 用户最想知道的就是"为什么"。
            // 只说"已取消"而不说原因，是最容易招致投诉的通知
            case "CANCELLED" -> "订单 #" + event.getOrderId() + " 已取消"
                    + (event.getReason() == null ? "" : "：" + event.getReason());
            default -> null;   // PENDING 等内部状态不打扰用户
        };
    }

    private String truncate(String s) {
        if (s == null) return "未知错误";
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
