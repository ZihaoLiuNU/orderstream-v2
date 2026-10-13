package com.orderstream.order.service;

import com.orderstream.common.security.CurrentUser;
import com.orderstream.common.web.BusinessException;
import com.orderstream.common.web.ForbiddenException;
import com.orderstream.common.web.ResourceNotFoundException;
import com.orderstream.order.client.MenuServiceClient;
import com.orderstream.order.dto.CreateOrderRequest;
import com.orderstream.order.dto.OrderResponse;
import com.orderstream.order.dto.UpdateOrderStatusRequest;
import com.orderstream.order.entity.Order;
import com.orderstream.order.repository.OrderIdempotencyKeyRepository;
import com.orderstream.order.repository.OrderRepository;
import com.orderstream.order.saga.OrderSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单应用服务：编排远程调用和事务，本身【不开事务】。
 *
 * 分工：
 *   OrderService            —— 编排：鉴权、远程调用、委托给事务方法
 *   OrderPersistenceService —— 事务：所有数据库写入
 *   OrderSagaOrchestrator   —— Saga 状态推进和补偿
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderIdempotencyKeyRepository idempotencyKeyRepository;
    private final MenuServiceClient menuServiceClient;
    private final OrderPersistenceService persistenceService;
    private final OrderSagaOrchestrator sagaOrchestrator;

    // ======================================================================
    // 下单
    // ======================================================================

    /**
     * 创建订单。
     *
     * 【注意这个方法上没有 @Transactional】—— 这是刻意的，见 OrderPersistenceService
     * 类注释里关于"远程调用不能放在事务里"的说明。
     *
     * 执行顺序：
     *   1. 幂等检查（独立的只读事务，很快）
     *   2. 并行调用 menu-service（【事务外】，可能耗时 50~2000ms）
     *   3. 写数据库 + 启动 Saga（一个短事务）
     */
    public OrderResponse createOrder(String idempotencyKey, CreateOrderRequest request) {
        CurrentUser user = CurrentUser.get();

        // --------------------------------------------------------------
        // 1. 幂等检查
        // --------------------------------------------------------------
        if (idempotencyKey != null) {
            var existing = idempotencyKeyRepository
                    .findByIdempotencyKeyAndUserId(idempotencyKey, user.getUserId());

            if (existing.isPresent()) {
                Long existingOrderId = existing.get().getOrderId();
                log.info("[下单] 幂等命中，返回已有订单 key={} userId={} orderId={}",
                        idempotencyKey, user.getUserId(), existingOrderId);

                /*
                 * 【返回已有订单，而不是报错】
                 *
                 * 这是幂等设计里一个关键的语义选择：
                 *   重复请求应该得到【和第一次一样的成功响应】，而不是一个 409 冲突错误。
                 *
                 * 理由：客户端重试是因为它【不知道】第一次成功了。
                 *   如果返回错误，客户端的错误处理逻辑会告诉用户"下单失败"，
                 *   但实际上订单已经创建了 —— 用户会以为没下单成功而再下一次，
                 *   或者去申诉。幂等机制反而制造了更大的混乱。
                 *
                 *   返回原结果，客户端就得到了它本该在第一次收到的响应，
                 *   整个重试对它是完全透明的。这才是幂等的意义：
                 *   "执行一次和执行多次，对调用方而言无法区分"。
                 */
                return toResponse(loadOwnedOrder(existingOrderId, user));
            }
        }

        // --------------------------------------------------------------
        // 2. 并行拉取餐厅 + 菜品（事务外）
        // --------------------------------------------------------------
        Set<Long> menuItemIds = request.getItems().stream()
                .map(CreateOrderRequest.Item::getMenuItemId)
                .collect(Collectors.toSet());

        /*
         * 【去重后的 ID 数量 != 请求里的条目数】
         *   用户可能在请求里写两条 {menuItemId: 5, quantity: 1}。
         *   Set 去重后只有一个 ID，但下面构建订单时会生成两条明细。
         *
         *   这是可以接受的（两条明细合计 2 份），但如果业务要求合并，
         *   就该在这里显式合并。我选择保留原样 ——
         *   因为"用户加了两次同一道菜"和"用户点了 2 份"在某些场景下语义不同
         *   （比如一份要辣一份不要）。
         *   重点是这个行为是【被想过的】，不是碰巧如此。
         */
        var snapshot = menuServiceClient.fetchForOrder(request.getRestaurantId(), menuItemIds);

        // --------------------------------------------------------------
        // 3. 落库 + 启动 Saga（短事务）
        // --------------------------------------------------------------
        Order order = persistenceService.createOrderAndStartSaga(
                user.getUserId(), idempotencyKey, request, snapshot);

        return toResponse(order);
    }

    // ======================================================================
    // 查询
    // ======================================================================

    /**
     * 查单个订单。
     *
     * ========================================================================
     * 【修复原版最严重的安全漏洞：BOLA / IDOR】
     * ========================================================================
     *
     * 原版的实现是：
     *     public OrderResponse getOrderById(Long id) {
     *         Order order = orderRepository.findById(id).orElseThrow(...);
     *         return OrderResponse.from(order);     // 谁的订单都返回！
     *     }
     *
     * Controller 上有 @PreAuthorize("hasAnyRole('USER','ADMIN')")，
     * 看起来"有权限控制"，但它只回答了【你登录了吗】，
     * 完全没回答【这是你的订单吗】。
     *
     * 任何登录用户执行：
     *     for i in $(seq 1 10000); do curl .../api/orders/$i -H "Authorization: Bearer $MY_TOKEN"; done
     * 就能拖走全站所有人的订单：谁在哪家餐厅点了什么、花了多少钱。
     *
     * 这是 OWASP API Security Top 10 的【第一名】：
     *   API1:2023 Broken Object Level Authorization（也叫 IDOR）。
     *
     * 【它为什么常年排第一】
     *   因为它看起来像已经防护了。代码里确实有 @PreAuthorize，
     *   review 时很容易扫过去。RBAC 和对象级授权是【两个正交的维度】：
     *     RBAC      回答：你这个角色能调用哪些接口
     *     对象级授权 回答：你能访问哪些具体的数据行
     *   只做前者是绝大多数 API 泄露事故的根因。
     *
     * 【为什么校验必须写在 Service 层而不是 Controller】
     *   Controller 是入口之一，不是唯一入口。将来：
     *     - 加一个 GraphQL 接口
     *     - 加一个内部 RPC
     *     - 加一个批量导出的定时任务
     *   任何新调用方都会绕过 Controller 的检查。
     *   把它放在 Service（或更好：放在实体上，见 Order.isOwnedBy）
     *   就保证了无论从哪条路进来都会被挡住。
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        return toResponse(loadOwnedOrder(orderId, CurrentUser.get()));
    }

    /**
     * 加载订单并校验归属。所有按 ID 取单个订单的路径都必须走这里。
     */
    private Order loadOwnedOrder(Long orderId, CurrentUser user) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("订单不存在: " + orderId));

        if (!user.isAdmin() && !order.isOwnedBy(user.getUserId())) {
            /*
             * 【这里有个细节：抛 403 还是 404？】
             *
             * 抛 403（当前实现）：告诉调用方"这条数据存在，但你没权限"。
             * 抛 404：假装数据不存在。
             *
             * 抛 404 更安全 —— 它连"这个 ID 存在"这个信息都不泄露，
             * 攻击者无法通过响应码差异来枚举出哪些订单 ID 是有效的。
             * 这叫"防资源枚举"。
             *
             * 我选 403 是因为：订单 ID 是自增的、本来就可枚举，
             * 隐藏存在性收益很小；而明确的 403 能让日志和监控区分出
             * "越权尝试"和"查了个不存在的单"，这对安全告警更有价值。
             *
             * 如果 ID 是 UUID 或资源本身是机密的（比如医疗记录、执法影像），
             * 那就必须返回 404。这个选择取决于威胁模型，没有普适答案。
             */
            log.warn("[安全] 越权访问订单被拒绝 userId={} 尝试访问 orderId={}（属于 userId={}）",
                    user.getUserId(), orderId, order.getUserId());
            throw new ForbiddenException("订单 " + orderId + " 不属于当前用户");
        }
        return order;
    }

    /**
     * 我的订单列表。
     *
     * 【两个修复】
     *   1) 分页。原版的 findByUserIdOrderByCreatedAtDesc 返回全部订单 ——
     *      一个下了三年外卖的重度用户可能有上千单，一次全查出来并序列化成 JSON，
     *      响应体几 MB，服务端内存飙升。任何列表接口都必须分页，没有例外。
     *
     *   2) N+1。用 @EntityGraph 一次 JOIN 取回明细，
     *      而不是每个订单再查一次。见 OrderRepository 里的详细说明。
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listMyOrders(Pageable pageable) {
        CurrentUser user = CurrentUser.get();
        return orderRepository.findByUserId(user.getUserId(), pageable)
                .map(OrderResponse::from);
    }

    /**
     * 餐厅后台：查活跃订单。仅管理员。
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listActiveOrdersForRestaurant(Long restaurantId, Pageable pageable) {
        return orderRepository.findByRestaurantIdAndStatusIn(
                        restaurantId,
                        List.of(Order.OrderStatus.PENDING,
                                Order.OrderStatus.CONFIRMED,
                                Order.OrderStatus.PREPARING),
                        pageable)
                .map(OrderResponse::from);
    }

    // ======================================================================
    // 状态变更
    // ======================================================================

    /**
     * 管理员推进订单状态（CONFIRMED -> PREPARING -> DELIVERED）。
     *
     * 注意这里【不需要】手写状态校验 —— transitionTo() 里的状态机会拒绝非法流转。
     * 原版把校验写成 Service 的私有方法，结果 Saga 那条路径绕过了它。
     * 把规则放进实体后，所有路径自动受保护。
     */
    @Transactional
    public OrderResponse updateStatus(Long orderId, UpdateOrderStatusRequest request) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("订单不存在: " + orderId));

        Order.OrderStatus previous = order.getStatus();

        // 管理员不能用这个接口取消订单：取消需要走补偿流程（退款 + 释放库存），
        // 不是简单改个状态。把它挡在这里，强制走 cancelOrder()
        if (request.getStatus() == Order.OrderStatus.CANCELLED) {
            throw new BusinessException("取消订单请使用 DELETE /api/orders/{id}，需要执行退款与库存释放补偿");
        }

        order.transitionTo(request.getStatus(), null);

        // 状态变了要通知用户，走 outbox 保证原子性
        sagaOrchestrator.publishStatusChanged(order, previous, "管理员操作");

        log.info("[订单] 管理员推进状态 orderId={} {} -> {}", orderId, previous, order.getStatus());
        return toResponse(order);
    }

    /**
     * 取消订单。
     *
     * 【这是一个真正的分布式补偿，不是简单地改个状态】
     *   订单已经 CONFIRMED 意味着：钱收了、库存扣了。
     *   取消必须把这两件事都撤销：
     *     - 发起退款（payment-service）
     *     - 释放库存（menu-service）
     *
     *   这两个补偿动作通过 outbox 发出，和"把订单标记为 CANCELLED"在同一个事务里。
     *   所以不会出现"订单显示已取消但钱没退"的情况。
     *
     * 归属校验同样必不可少 —— 否则任何人都能取消别人的订单，
     * 这比"读别人的订单"危害更大（是写操作，且造成实际损失）。
     */
    @Transactional
    public OrderResponse cancelOrder(Long orderId, String reason) {
        CurrentUser user = CurrentUser.get();
        Order order = loadOwnedOrder(orderId, user);

        if (order.isTerminal()) {
            throw new BusinessException("订单已处于终态（" + order.getStatus() + "），无法取消");
        }

        sagaOrchestrator.compensateAndCancel(order, reason == null ? "用户主动取消" : reason);
        return toResponse(order);
    }

    private OrderResponse toResponse(Order order) {
        return OrderResponse.from(order);
    }
}
