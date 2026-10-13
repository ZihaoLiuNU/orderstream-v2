package com.orderstream.cart.kafka;

import com.orderstream.cart.service.CartService;
import com.orderstream.common.Topics;
import com.orderstream.common.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 下单成功后自动清空购物车。
 *
 * ============================================================================
 * 【这是"同一条事件被多个消费者组独立消费"的活例子】
 * ============================================================================
 *
 *   order.status.changed 这个 topic 同时被两个消费者组订阅：
 *     notification-service -> 发推送通知
 *     cart-service         -> 清空购物车
 *
 *   两个组各自维护 offset，所以每条消息两边【各收到一份】。
 *   这就是发布订阅(pub/sub)模型，也是 Kafka 相对传统消息队列的关键能力。
 *
 *   而 order-service【完全不知道】这两个消费者的存在。
 *   它只是宣布了一个事实"订单 42 从 PENDING 变成了 CONFIRMED"，
 *   谁关心这个事实、拿它去做什么，是消费方自己的事。
 *
 *   这就是"用 Kafka 解耦下游服务"的实质：
 *   【下游的增减不会反向影响上游】。
 *   明天要加 analytics-service 做实时大盘？
 *   新起一个消费者组就行，order-service 一行代码都不用改、不用重新部署。
 *
 * ============================================================================
 * 【为什么这个消费者不需要 ProcessedEvent 去重表】
 * ============================================================================
 *
 *   清空购物车是【天然幂等】的操作：
 *     DELETE 一个 key，执行一次和执行十次结果完全相同。
 *   Kafka 重复投递不会造成任何问题。
 *
 *   对比 menu-service 的"扣库存"（stock = stock - 1）：
 *     那是累加操作，执行两次就多扣一份，必须有去重表。
 *
 *   判断标准就一条：f(f(x)) == f(x) 是否成立。
 *   成立就不需要额外机制，不成立就必须有。
 *
 *   【不要因为"别的消费者都加了去重表"就跟着加】——
 *   一张多余的表意味着：多一次数据库往返、多一份要清理的数据、
 *   多一个可能出问题的地方。对 cart-service 来说，
 *   它甚至意味着要为此引入整个 JPA 技术栈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private static final String CONSUMER_GROUP = "cart-service";

    private final CartService cartService;

    @KafkaListener(topics = Topics.ORDER_STATUS_CHANGED, groupId = CONSUMER_GROUP)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {

        /*
         * 只在订单【首次创建】时清空购物车。
         *
         * 判断依据是 previousStatus == null，也就是这是订单的第一个状态事件。
         * 后续的 PENDING -> CONFIRMED、CONFIRMED -> PREPARING 等等
         * 都不该再动购物车 —— 那时用户可能已经在加下一单的菜了，
         * 清空会把他新加的东西删掉。
         *
         * 【这类"只在特定转换时触发副作用"的判断，是事件驱动架构里
         *   最容易出错的地方】。
         *   事件流里的每条消息都长得差不多，但触发时机差一点，
         *   用户体验就完全不同。
         *   所以事件里必须带上 previousStatus —— 没有它就无法区分
         *   "刚创建"和"状态流转"，只能瞎猜。
         *
         * 设计事件 schema 时的一条经验：
         *   状态变更类事件要同时带上【旧值和新值】，
         *   只带新值的话，消费方永远无法判断"发生了什么变化"。
         */
        if (event.getPreviousStatus() != null) {
            return;
        }

        log.info("[购物车] 订单已创建，清空购物车 userId={} orderId={}",
                event.getUserId(), event.getOrderId());
        cartService.clearCart(event.getUserId());
    }
}
