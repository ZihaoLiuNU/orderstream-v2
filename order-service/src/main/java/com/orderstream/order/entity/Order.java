package com.orderstream.order.entity;

import com.orderstream.common.web.BusinessException;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 订单聚合根。
 *
 * ============================================================================
 * 【这个类和原版最大的区别：状态机被封装进了实体内部】
 * ============================================================================
 *
 * 原版把状态流转校验写成 OrderService 里的一个 private 方法 validateTransition()，
 * 只在管理员改状态那条路径上调用。结果是 Saga 编排器那条路径【完全绕过了校验】，
 * 直接 order.setStatus(CONFIRMED)。
 *
 * 后果是一个真实的数据损坏 bug：
 *   支付成功 -> CONFIRMED -> 管理员推进到 PREPARING
 *   -> 旧的 PaymentResultEvent 被重投 -> 无条件 setStatus(CONFIRMED)
 *   -> 订单从 PREPARING 【倒退】回 CONFIRMED
 *
 * 把规则放进实体、并且【不暴露 setStatus】，就让这个 bug 在结构上不可能发生：
 * 任何想改状态的代码都必须经过 transitionTo()，没有旁路。
 *
 * 这就是「充血模型(rich domain model)」相对「贫血模型(anemic model)」的核心价值：
 * 不变量(invariant)由拥有数据的对象自己守护，而不是指望每个调用方都记得校验。
 * 贫血模型下，规则散落在各个 Service 里，加一条新路径就漏一次。
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    /**
     * 注意这里【只有 getter，没有 public setter】（类上是 @Getter 不是 @Data）。
     * 想改状态只能调 transitionTo()。这是上面那段设计的落地。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Setter
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /**
     * 乐观锁。
     *
     * 【它在这里防的是什么】
     *   两条路径会并发修改同一个订单：
     *     路径A：管理员调 PATCH /api/orders/1/status 把订单推进到 PREPARING
     *     路径B：Saga 编排器消费到支付结果，把订单改成 CONFIRMED
     *
     *   没有 @Version 的话（原版就是），在 Postgres 默认的 READ COMMITTED 下：
     *     A 读到 status=CONFIRMED
     *     B 读到 status=CONFIRMED
     *     A 写入 PREPARING
     *     B 写入 CONFIRMED      <- A 的修改被静默覆盖，这叫 lost update
     *
     *   加上 @Version 后，B 的 UPDATE 会带上 WHERE version = ?，
     *   匹配 0 行 -> 抛 OptimisticLockingFailureException -> 事务回滚 -> Kafka 重投
     *   -> 重投时读到最新状态，走状态机校验，正确地拒绝非法流转。
     *
     *   【关键点】：READ COMMITTED 隔离级别【不会】阻止 lost update。
     *   这是非常普遍的误解。它只保证读不到未提交数据，
     *   完全不管"读-改-写"这个序列的原子性。
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 订单明细属于订单聚合内部，所以用级联关联是恰当的
     * （对比 menu-service 里 Restaurant 刻意不关联 MenuItem —— 那是两个独立聚合）。
     *
     * FetchType.LAZY + 查询时显式 @EntityGraph，是避免 N+1 的标准组合：
     *   LAZY 保证不会在你不知情时偷偷查库；
     *   @EntityGraph 保证在你确实需要时一次查完。
     * 用 EAGER 则是反过来 —— 每次查订单都强制 JOIN 明细，哪怕只想看个状态。
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    // ======================================================================
    // 状态机
    // ======================================================================

    public enum OrderStatus {
        /** 已创建，Saga 进行中（库存预留 / 支付） */
        PENDING,
        /** 支付成功，Saga 完成 */
        CONFIRMED,
        /** 餐厅开始备餐 */
        PREPARING,
        /** 已送达（终态） */
        DELIVERED,
        /** 已取消（终态） */
        CANCELLED
    }

    /**
     * 合法流转表。
     *
     * 【把规则写成数据而不是 if-else 的好处】
     *   1) 一眼看得出全貌，review 时容易发现漏洞
     *   2) 加状态时只改这张表，不用在散落的分支里找
     *   3) 可以直接用它生成状态图文档
     *
     * 【注意 DELIVERED 和 CANCELLED 映射到空集合 —— 这是终态】
     *   原版的写法是 `if (to == CANCELLED) return;`（任何状态都能取消），
     *   这意味着【已送达的订单可以被取消】。
     *   业务上这是错的：餐已经吃了，取消意味着要退款，
     *   但食物无法退回 —— 这种情况必须走售后/退款流程，
     *   而不是简单地把订单状态改成 CANCELLED。
     *   终态就该是终态，这是状态机最基本的约束。
     */
    private static final Map<OrderStatus, EnumSet<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            OrderStatus.PENDING,   EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.PREPARING, OrderStatus.CANCELLED),
            OrderStatus.PREPARING, EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
            OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),   // 终态
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)    // 终态
    );

    /**
     * 改变订单状态。这是【唯一】能改状态的入口。
     *
     * @throws BusinessException 流转非法时
     */
    public void transitionTo(OrderStatus target, String reason) {
        if (this.status == target) {
            /*
             * 【幂等地接受"转到当前状态"】
             *
             * 这个分支不是偷懒，是刻意的：
             *   Kafka 是 at-least-once，重复投递必然发生。
             *   一个已经处理过的 PaymentResultEvent 重投时，
             *   订单已经是 CONFIRMED 了，再次要求转到 CONFIRMED —— 这应该被视为
             *   "无事发生"，而不是抛异常。
             *
             *   如果这里抛异常，Kafka 会重试、重试耗尽后进死信队列，
             *   然后有人半夜被告警叫醒，查了半天发现是个完全正常的重复消息。
             *
             * 注意这【不能替代】eventId 去重（ProcessedEvent 表）：
             *   状态幂等只对"赋值型"操作成立。如果处理逻辑里还有
             *   "发一条短信"或"扣一笔钱"，那重复执行依然会出事。
             *   两层幂等各管各的。
             */
            return;
        }

        EnumSet<OrderStatus> allowed = ALLOWED_TRANSITIONS.get(this.status);
        if (allowed == null || !allowed.contains(target)) {
            throw new BusinessException(String.format(
                    "非法的订单状态流转: %s -> %s（订单 %d）", this.status, target, this.id));
        }

        this.status = target;
        if (target == OrderStatus.CANCELLED) {
            this.cancelReason = reason;
        }
    }

    /** 订单是否还在 Saga 进行中 */
    public boolean isInFlight() {
        return status == OrderStatus.PENDING;
    }

    /** 是否已到终态，不会再变化 */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(status).isEmpty();
    }

    /**
     * 对象级授权：这个订单是不是这个用户的。
     *
     * 【把它放在实体上，而不是散落在 Service 各处】
     *   原版的 getOrderById 完全没有这个检查 —— Controller 上的
     *   @PreAuthorize("hasAnyRole('USER','ADMIN')") 只验证了"你登录了"，
     *   没验证"这是你的订单"。
     *   结果：任何登录用户遍历 /api/orders/1,2,3... 就能读到所有人的订单。
     *
     *   这是 OWASP API Security Top 10 的第一名：
     *   BOLA / Broken Object Level Authorization（也叫 IDOR）。
     *
     *   它之所以常年排第一，是因为它【看起来像有权限控制】——
     *   代码里确实有 @PreAuthorize，review 时很容易一眼扫过去觉得没问题。
     *   但 RBAC 回答的是"你这类人能做什么操作"，
     *   完全回答不了"你能碰哪几条数据"。这是两个正交的问题。
     */
    public boolean isOwnedBy(Long candidateUserId) {
        return this.userId.equals(candidateUserId);
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    /**
     * 设置订单总额。
     *
     * 【为什么是这个方法而不是一个普通的 setTotalAmount】
     *   总额是订单最敏感的字段 —— 它直接决定扣用户多少钱。
     *   开放一个 public setter 意味着代码里任何地方都能改它，
     *   而且 review 时很难注意到。
     *
     *   这个方法加了两条不变量守护：
     *     1) 只能在订单还没进入 Saga 之前设置（PENDING 状态）
     *     2) 不能是负数
     *
     *   把"什么时候可以改、能改成什么"的规则写进实体，
     *   就不需要指望每个调用方都记得检查。
     *   这和 transitionTo() 是同一个设计思路：让非法状态无法被表达。
     */
    public void applyTotal(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new BusinessException("订单金额不能为空或负数");
        }
        if (this.status != OrderStatus.PENDING) {
            throw new BusinessException("订单已进入处理流程，金额不可再修改");
        }
        this.totalAmount = amount;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
