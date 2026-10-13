package com.orderstream.order.repository;

import com.orderstream.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * ========================================================================
     * 【修复原版的 N+1 查询问题】
     * ========================================================================
     *
     * 原版是这样的：
     *     List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
     *     orders.stream().map(OrderResponse::from)   // from() 里访问了 LAZY 关联
     *
     * OrderResponse.from() 里访问了三个懒加载属性：
     *     order.getRestaurant().getName()      -> 每个订单 1 次查询
     *     order.getOrderItems()                -> 每个订单 1 次查询
     *     item.getMenuItem().getName()         -> 每个明细 1 次查询
     *
     * 一个有 50 个订单、每单 3 个菜品的用户：
     *     1 (主查询) + 50 (餐厅) + 50 (明细) + 150 (菜品) = 251 次数据库往返
     *
     * 【关键认知：索引解决不了这个问题】
     *   "加索引优化查询"针对的是【单次查询的耗时】。
     *   N+1 的问题是【查询的次数】。
     *   每次往返就算只要 0.5ms，251 次也是 125ms，而且这 125ms 全是网络等待，
     *   数据库 CPU 几乎没工作。加再多索引也一点用没有。
     *   这两类性能问题要分开诊断：慢查询看 EXPLAIN，N+1 看 SQL 条数。
     *
     * @EntityGraph 让 Hibernate 生成一条带 LEFT JOIN FETCH 的 SQL，
     * 一次把订单和明细全部取回。251 次变成 1 次。
     *
     * 【为什么 attributePaths 里没有"restaurant"和"menuItem"】
     *   因为在新架构下它们根本不存在了：
     *     - restaurant 只是个 restaurant_id 数字（跨服务，本地没有这张表）
     *     - 菜品名称已经作为快照存进了 order_items.menu_item_name
     *   跨服务的数据冗余顺带消灭了两类 N+1。
     *   这是个有意思的观察：好的服务边界划分，往往连带解决了性能问题 ——
     *   因为它强迫你在写入时就把需要的数据准备好，而不是在读取时到处去捞。
     */
    @EntityGraph(attributePaths = "items")
    Page<Order> findByUserId(Long userId, Pageable pageable);

    /**
     * 查单个订单（含明细）。
     *
     * 同样用 EntityGraph：订单详情页一定要显示明细，
     * 让它变成一次 JOIN 查询而不是两次往返。
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);

    /**
     * 餐厅后台：查某家餐厅的活跃订单。
     *
     * 走 idx_orders_restaurant_pending 部分索引
     *   (restaurant_id, created_at) WHERE status IN ('PENDING','CONFIRMED','PREPARING')
     *
     * 【这里没有用 EntityGraph，是刻意的】
     *   餐厅后台的列表页只需要"订单号、时间、状态、金额"，不需要明细。
     *   强行 JOIN 明细会让返回的行数翻几倍（一个订单 5 个菜品就是 5 行），
     *   然后 Hibernate 再去重 —— 白白传输和处理了大量重复数据。
     *   这叫"笛卡尔积爆炸"，是滥用 JOIN FETCH 的典型后果。
     *
     *   规则：只 fetch 你这个用例真正会用到的关联。
     *   同一个实体在不同用例下该用不同的抓取策略，这正是
     *   @EntityGraph 写在【查询方法】上而不是【实体】上的原因。
     */
    Page<Order> findByRestaurantIdAndStatusIn(Long restaurantId,
                                              java.util.Collection<Order.OrderStatus> statuses,
                                              Pageable pageable);
}
