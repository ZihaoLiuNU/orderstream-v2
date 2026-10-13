package com.orderstream.order.controller;

import com.orderstream.order.dto.*;
import com.orderstream.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 下单。
     *
     * 【Idempotency-Key 头】
     *   客户端自己生成一个 UUID 放在这个头里。
     *   同一个 key 重复提交只会产生一个订单，重复请求直接返回第一次的结果。
     *
     *   头名用 "Idempotency-Key" 是行业惯例（Stripe、Square、PayPal 都用这个），
     *   IETF 也有相应的草案。用标准名字的好处是客户端 SDK 和网关能自动识别。
     *
     *   required = false：不强制。但生产环境应该强制要求 ——
     *   把"防重复下单"的责任交给客户端"记得带"，最终一定会有客户端忘记。
     *   这里设成可选是为了方便压测脚本和手工调试。
     *
     *   @Size(max=64) 防止有人塞一个 10MB 的字符串进来当 key ——
     *   任何来自客户端的字符串都必须限长，否则就是一个内存放大攻击面。
     */
    @PostMapping
    @PreAuthorize("hasRole('USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Size(max = 64, message = "Idempotency-Key 最长 64 字符") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        return orderService.createOrder(idempotencyKey, request);
    }

    /**
     * 查单个订单。
     *
     * 【注意这里的 @PreAuthorize 只写了 hasAnyRole】
     *   它只能回答"你登录了吗"。真正的归属校验在 Service 层
     *   （OrderService.loadOwnedOrder），那里才能拿到订单数据做对象级判断。
     *
     *   这正是 BOLA 漏洞的成因：很多人看到 Controller 上有 @PreAuthorize
     *   就以为权限已经控制住了。注解只是第一层，不是全部。
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public OrderResponse getOrder(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    /**
     * 我的订单列表（分页）。
     *
     * @PageableDefault 里显式写 size=20 和排序。
     * 另外必须在 application.yml 里配 spring.data.web.pageable.max-page-size，
     * 否则客户端传 ?size=100000 依然能绕过默认值 —— 这时"分页"等于没有。
     */
    @GetMapping("/my")
    @PreAuthorize("hasRole('USER')")
    public Page<OrderResponse> myOrders(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.listMyOrders(pageable);
    }

    /**
     * 餐厅后台：查活跃订单。仅管理员。
     */
    @GetMapping("/restaurant/{restaurantId}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponse> activeOrders(
            @PathVariable Long restaurantId,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return orderService.listActiveOrdersForRestaurant(restaurantId, pageable);
    }

    /**
     * 推进订单状态。仅管理员（代表餐厅后台）。
     *
     * 用 PATCH 而不是 PUT：
     *   PUT 的语义是"用请求体【整体替换】这个资源"，
     *   PATCH 是"部分修改"。我们只改 status 一个字段，PATCH 更准确。
     *   语义正确不只是洁癖 —— 中间件会据此决定能否缓存、能否重试。
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse updateStatus(@PathVariable Long id,
                                      @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderService.updateStatus(id, request);
    }

    /**
     * 取消订单（会触发退款 + 库存释放的补偿流程）。
     *
     * 用 DELETE 但返回订单对象而不是 204：
     *   订单并没有被【删除】，只是状态变成了 CANCELLED。
     *   严格说用 POST /api/orders/{id}/cancel 更准确（这是一个动作，不是删除资源）。
     *   我选 DELETE 是因为对客户端更直观，但返回完整对象来表明
     *   "资源还在，只是状态变了"。
     *
     *   这是 REST 语义在真实业务里的常见妥协点 ——
     *   重要的是这个选择是【被想过的】并且在文档里说清楚了。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public OrderResponse cancelOrder(@PathVariable Long id,
                                     @RequestBody(required = false) CancelOrderRequest request) {
        return orderService.cancelOrder(id, request == null ? null : request.getReason());
    }
}
