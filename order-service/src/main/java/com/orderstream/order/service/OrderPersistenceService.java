package com.orderstream.order.service;

import com.orderstream.common.Topics;
import com.orderstream.common.event.OrderLineItem;
import com.orderstream.common.event.OrderStatusChangedEvent;
import com.orderstream.common.event.StockReservationRequestedEvent;
import com.orderstream.common.web.BusinessException;
import com.orderstream.messaging.outbox.OutboxPublisher;
import com.orderstream.order.client.MenuServiceClient.MenuSnapshot;
import com.orderstream.order.client.MenuItemView;
import com.orderstream.order.dto.CreateOrderRequest;
import com.orderstream.order.entity.Order;
import com.orderstream.order.entity.OrderIdempotencyKey;
import com.orderstream.order.entity.OrderItem;
import com.orderstream.order.repository.OrderIdempotencyKeyRepository;
import com.orderstream.order.repository.OrderRepository;
import com.orderstream.order.repository.OrderSagaRepository;
import com.orderstream.order.saga.OrderSaga;
import com.orderstream.order.saga.SagaState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 下单的【事务性】部分。
 *
 * ============================================================================
 * 【为什么要单独拆成一个类 —— Spring AOP 自调用失效问题】
 * ============================================================================
 *
 * 本来最自然的写法是把这段逻辑作为 OrderService 的一个 private/public 方法：
 *
 *     public OrderResponse createOrder(...) {       // 不加事务
 *         MenuSnapshot snapshot = menuClient.fetch(...);   // 远程调用
 *         return this.persist(snapshot, ...);              // 加了 @Transactional
 *     }
 *
 * 这样写【@Transactional 会完全失效】，而且不会有任何报错。
 *
 * 原因：Spring 的 @Transactional 是靠【动态代理】实现的。
 *   容器注入给你的不是 OrderService 本身，而是一个包装它的代理对象。
 *   代理在"进入方法前开启事务、退出后提交"。
 *   但 this.persist() 是【在对象内部直接调用】，根本没经过代理 ——
 *   于是事务增强被完全跳过了。
 *
 *   后果极其隐蔽：订单保存了、outbox 事件也保存了，但它们分属两个独立的
 *   自动提交事务，Outbox 模式承诺的原子性【悄悄失效了】，
 *   而所有测试可能都还是绿的（因为正常路径下两个都会成功）。
 *   只有在极少数"订单保存后崩溃"的情况下才会暴露，那时已经在生产了。
 *
 * 解法有三种：
 *   1) 拆成两个 Bean（本文件采用）—— 跨 Bean 调用必然经过代理，最清晰
 *   2) 注入自己的代理（@Lazy self）—— 能用，但代码很怪，新人看不懂
 *   3) 改用 AspectJ 编织 —— 需要额外的编译期/运行期配置，太重
 *
 * ============================================================================
 * 【第二个理由：远程调用绝不能放在事务里】
 * ============================================================================
 *
 * 即使不考虑代理问题，把 menu-service 的 HTTP 调用放进 @Transactional 也是错的：
 *   进入事务的那一刻就借走了一条数据库连接，直到事务结束才归还。
 *   如果事务里做一次 2 秒的远程调用，这条连接就被占用 2 秒什么也不干。
 *
 *   连接池只有 15 条。menu-service 一变慢，15 个并发请求就能把连接池占光，
 *   然后【所有】数据库操作全部阻塞 —— 包括那些根本不碰 menu-service 的接口。
 *   一个下游服务的延迟，通过连接池这个共享资源，放大成了全服务故障。
 *
 *   规则：事务应该尽可能短，且【只包含数据库操作】。
 *        所有 IO（HTTP、文件、消息）要么在事务前做，要么在事务后做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private final OrderRepository orderRepository;
    private final OrderSagaRepository orderSagaRepository;
    private final OrderIdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxPublisher outboxPublisher;

    /**
     * 创建订单并启动 Saga。
     *
     * 这个事务里发生的 5 件事，要么全成功、要么全回滚：
     *   1. 写 orders 表
     *   2. 写 order_items 表
     *   3. 写 order_sagas 表（Saga 初始状态）
     *   4. 写 order_idempotency_keys 表（幂等记录）
     *   5. 写 outbox_events 表（待发送的库存预留请求）
     *
     * 【第 5 项是整个设计的关键】
     *   原版代码在这里直接调 kafkaTemplate.send()，消息会在事务提交【之前】
     *   就发出去。于是：
     *     - 事务回滚 -> 订单不存在，但支付已经被触发 -> 用户被扣款
     *     - 消息先到 -> 下游查不到订单 -> 重试耗尽 -> 消息丢弃 -> 订单永久卡死
     *
     *   改成写 outbox 表之后，"发消息"变成了和"写订单"一样的数据库操作，
     *   被同一个本地事务覆盖。两者的原子性由数据库的 ACID 直接保证，
     *   不需要任何分布式事务协调者。
     */
    @Transactional
    public Order createOrderAndStartSaga(Long userId,
                                         String idempotencyKey,
                                         CreateOrderRequest request,
                                         MenuSnapshot snapshot) {

        Map<Long, MenuItemView> itemsById = snapshot.itemsById();

        Order order = Order.builder()
                .userId(userId)
                .restaurantId(request.getRestaurantId())
                .status(Order.OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)   // 下面按明细累加
                .build();

        BigDecimal total = BigDecimal.ZERO;

        for (CreateOrderRequest.Item requested : request.getItems()) {
            MenuItemView menuItem = itemsById.get(requested.getMenuItemId());

            if (menuItem == null) {
                throw new BusinessException("菜品不存在: " + requested.getMenuItemId());
            }

            /*
             * 【校验菜品属于这家餐厅 —— 修复原版的一个真实业务漏洞】
             *
             * 原版完全没有这个检查。后果是：
             *   我可以向餐厅 B 下单，但点的是餐厅 A 的菜。
             *   订单会成功创建，餐厅 B 收到一个自己根本做不出来的订单。
             *
             * 更严重的是价格套利：
             *   如果餐厅 A 的同款菜比 B 便宜，攻击者就能按 A 的价格
             *   在 B 下单。这是直接的经济损失。
             *
             * 这类漏洞的共同特征是：【客户端提供了两个本该相关联的 ID，
             * 但服务端没有验证它们的关系】。
             * 只要接口接受多个 ID 参数，就一定要问：它们之间有没有必须成立的约束？
             */
            if (!menuItem.restaurantId().equals(request.getRestaurantId())) {
                log.warn("[下单] 菜品与餐厅不匹配 userId={} menuItemId={} 属于餐厅 {} 但下单到餐厅 {}",
                        userId, menuItem.id(), menuItem.restaurantId(), request.getRestaurantId());
                throw new BusinessException(
                        String.format("菜品「%s」不属于该餐厅", menuItem.name()));
            }

            if (Boolean.FALSE.equals(menuItem.inStock())) {
                throw new BusinessException(String.format("菜品「%s」已售罄", menuItem.name()));
            }

            OrderItem item = OrderItem.builder()
                    .menuItemId(menuItem.id())
                    // 【快照】菜品名和价格。之后餐厅改价改名都不影响这笔订单
                    .menuItemName(menuItem.name())
                    .unitPrice(menuItem.price())
                    .quantity(requested.getQuantity())
                    .build();

            order.addItem(item);
            total = total.add(item.subtotal());
        }

        // Lombok 的 @Getter 不生成 setter，这里用反射之外的方式：
        // 通过 builder 时已经设了 ZERO，这里需要一个受控的更新方法
        order.applyTotal(total);

        Order saved = orderRepository.save(order);

        // ------------------------------------------------------------------
        // 幂等记录。放在订单保存【之后】，因为它要引用 order_id
        // ------------------------------------------------------------------
        if (idempotencyKey != null) {
            try {
                idempotencyKeyRepository.save(OrderIdempotencyKey.builder()
                        .idempotencyKey(idempotencyKey)
                        .userId(userId)
                        .orderId(saved.getId())
                        .build());
            } catch (DataIntegrityViolationException e) {
                /*
                 * 主键冲突：两个并发请求带着同一个 key 同时进来，
                 * 都通过了前置的"查一下有没有"检查。
                 *
                 * 数据库的唯一约束是这里【唯一】可靠的并发防线 ——
                 * 应用层的 check-then-act 在并发下永远有竞态窗口。
                 *
                 * 抛出去让事务整体回滚，这一单不创建。
                 * 客户端重试时会查到另一个请求创建的订单，得到正确的结果。
                 */
                log.info("[下单] 幂等键并发冲突，回滚本次创建 key={} userId={}", idempotencyKey, userId);
                throw new BusinessException("重复的请求，请稍后查询订单状态");
            }
        }

        // ------------------------------------------------------------------
        // Saga 初始状态
        // ------------------------------------------------------------------
        String sagaId = UUID.randomUUID().toString();
        orderSagaRepository.save(OrderSaga.builder()
                .sagaId(sagaId)
                .orderId(saved.getId())
                .state(SagaState.STOCK_PENDING)
                .totalAmount(total)
                .build());

        // ------------------------------------------------------------------
        // Saga 步骤 1：请求预留库存。走 outbox，和上面的写入同一个事务
        // ------------------------------------------------------------------
        List<OrderLineItem> lineItems = saved.getItems().stream()
                .map(i -> OrderLineItem.builder()
                        .menuItemId(i.getMenuItemId())
                        .menuItemName(i.getMenuItemName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .build())
                .toList();

        outboxPublisher.publish(
                Topics.STOCK_RESERVATION_REQUESTED,
                // 用 orderId 做分区键：保证同一订单的所有事件严格有序。
                // Kafka 只保证分区内有序，同 key 必落同一分区
                String.valueOf(saved.getId()),
                StockReservationRequestedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(saved.getId())
                        .sagaId(sagaId)
                        .items(lineItems)
                        .build());

        // ------------------------------------------------------------------
        // 广播「订单已创建」领域事件
        // ------------------------------------------------------------------
        /*
         * 【previousStatus = null 是一个有意义的信号，不是偷懒】
         *
         * 它表示"这是这个订单的第一个状态事件"，也就是订单刚被创建。
         * 下游靠它区分「新订单」和「状态流转」：
         *   - cart-service 只在 previousStatus == null 时清空购物车。
         *     如果不区分，后续每次状态变更都会清一次购物车 ——
         *     用户下完单又加了新菜，会被莫名其妙地清掉。
         *   - notification-service 对 PENDING 状态不发通知
         *     （用户刚点完提交，不需要再告诉他"订单已创建"）。
         *
         * 【事件 schema 设计的一条经验】：
         *   状态变更类事件必须同时携带【旧值和新值】。
         *   只带新值的话，消费方永远无法判断"发生了什么变化"，
         *   只能靠自己维护一份状态副本去比对 —— 那就又制造了一个一致性问题。
         */
        outboxPublisher.publish(
                Topics.ORDER_STATUS_CHANGED,
                String.valueOf(saved.getId()),
                OrderStatusChangedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(saved.getId())
                        .userId(userId)
                        .previousStatus(null)
                        .newStatus(saved.getStatus().name())
                        .reason("订单已创建")
                        .build());

        log.info("[下单] 订单已创建，Saga 启动 orderId={} sagaId={} 金额={} 明细数={}",
                saved.getId(), sagaId, total, lineItems.size());

        return saved;
    }
}
