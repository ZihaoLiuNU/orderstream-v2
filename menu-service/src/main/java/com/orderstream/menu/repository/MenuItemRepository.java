package com.orderstream.menu.repository;

import com.orderstream.menu.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /**
     * 查某家餐厅的在售菜品，按名称排序。
     *
     * 这条查询完全被 idx_menu_items_restaurant_name 覆盖：
     *   索引定义是 (restaurant_id, name) WHERE available = TRUE
     *   - restaurant_id 等值过滤走索引前缀
     *   - ORDER BY name 直接利用索引的物理顺序，执行计划里【没有 Sort 节点】
     *   - available = TRUE 匹配部分索引条件，下架菜品根本不在索引里
     *
     * 对比只建单列 (restaurant_id) 索引：查询能走索引，但排序要额外做一次 Sort，
     * 数据量大时会变成 external merge sort（落磁盘），延迟陡增。
     * 这就是"加了索引"和"加对了索引"的区别。
     */
    List<MenuItem> findByRestaurantIdAndAvailableTrueOrderByName(Long restaurantId);

    /**
     * 批量按 ID 查询。order-service 下单时一次性取回所有菜品。
     *
     * 【修复原版的 N+1 问题】
     *   原版是在循环里逐个 menuItemRepository.findById(...)，
     *   10 个菜品 = 10 次数据库往返。每次往返在同机房也有 ~0.5ms 网络开销，
     *   跨可用区就是几 ms。用 IN 查询合并成一次，是最直接的优化。
     *
     * 【但 IN 列表不能无限长】
     *   Postgres 的查询参数上限是 65535 个，超过直接报错。
     *   而且超长 IN 列表会让 planner 放弃索引改走全表扫描。
     *   实际项目里应该分批（比如每 500 个一批）。
     *   这里购物车不可能有那么多菜品，所以没做分批 —— 但边界要知道在哪。
     */
    List<MenuItem> findByIdIn(Collection<Long> ids);

    /**
     * ========================================================================
     * 【原子条件扣库存 —— 整个项目里最重要的一条 SQL】
     * ========================================================================
     *
     * 这一条语句同时完成了"检查库存是否充足"和"扣减库存"两件事。
     * 关键在于条件 stock_quantity >= :quantity 写在了 UPDATE 的 WHERE 里。
     *
     * 【为什么不能写成"先查再改"】
     *
     *     // 错误写法
     *     MenuItem item = repo.findById(id);
     *     if (item.getStock() >= qty) {        // 检查
     *         item.setStock(item.getStock() - qty);   // 修改
     *         repo.save(item);
     *     }
     *
     *   这是经典的 check-then-act 竞态。库存只剩 1 份时：
     *     线程A 查到 stock=1，判断 1>=1 通过
     *     线程B 查到 stock=1，判断 1>=1 通过      <- 两个线程都认为可以卖
     *     线程A 写入 stock=0
     *     线程B 写入 stock=0
     *   结果：卖出去 2 份，库存只扣了 1 份。超卖。
     *
     *   Postgres 默认的 READ COMMITTED 隔离级别【不会】阻止这个 ——
     *   两个线程读到的都是已提交的数据，完全合法。
     *   只有 SERIALIZABLE 隔离级别能阻止，但那会大幅降低并发度。
     *
     * 【单条 UPDATE 为什么就安全了】
     *   数据库对单条语句内的行修改天然是原子的：
     *   Postgres 执行 UPDATE 时会对匹配到的行加【行级排他锁】，
     *   第二个事务的同一条 UPDATE 必须等第一个提交后才能执行，
     *   而且它会【重新求值 WHERE 条件】(这叫 EPQ，EvaluatePlanQual)。
     *   所以线程B 重新检查时会发现 stock 已经是 0，条件不满足，更新 0 行。
     *
     *   返回值就是受影响的行数：1 = 扣减成功，0 = 库存不足。
     *   不需要额外查询，不需要重试循环，不需要乐观锁重试。
     *
     * 【和乐观锁(@Version)的对比 —— 哪个更好】
     *   乐观锁：冲突时抛异常，调用方必须捕获并重试整个业务流程。
     *           在高并发抢购场景下，大量线程会反复重试再失败，
     *           形成"活锁"，吞吐急剧下降。
     *   条件UPDATE：冲突时数据库内部排队，每个线程只执行一次，
     *           要么成功要么明确失败，没有重试风暴。
     *
     *   结论：对"扣减计数器"这类【可交换的增量操作】，条件 UPDATE 明显更优。
     *        对"修改多个字段的完整对象"这类操作，乐观锁更合适
     *        （因为那时你要保护的是整个对象的一致性，不是单个数值）。
     *        所以我两个都留着，各管各的场景。
     *
     * 【为什么带 version = version + 1】
     *   保持和 JPA 乐观锁的一致性。如果这里绕过 version 直接改 stock，
     *   那么同一事务里通过 JPA 加载的 MenuItem 实体就会持有过期的 version，
     *   后续 JPA 更新会误判为冲突。手写 UPDATE 时同步维护 version 是必须的纪律。
     */
    @Modifying
    @Query(value = """
            UPDATE menu_items
            SET stock_quantity = stock_quantity - :quantity,
                version = version + 1
            WHERE id = :menuItemId
              AND available = TRUE
              AND stock_quantity >= :quantity
            """, nativeQuery = true)
    int tryDeductStock(@Param("menuItemId") Long menuItemId,
                       @Param("quantity") int quantity);

    /**
     * 归还库存（补偿事务用）。
     *
     * 注意它【没有】任何条件判断 —— 归还永远会成功。
     * 这是补偿事务的核心要求：补偿必须是"几乎不会失败"的操作，
     * 因为补偿失败了没有"补偿的补偿"可做，只能重试到成功或人工介入。
     *
     * 幂等性不在这一层保证，而在上层靠 stock_reservations.status 的状态机保证：
     * 已经是 RELEASED 的预留不会再次进入这个方法。
     * 如果把幂等判断写在这里（比如加个 WHERE 条件），反而会让逻辑分散、难以推理。
     */
    @Modifying
    @Query(value = """
            UPDATE menu_items
            SET stock_quantity = stock_quantity + :quantity,
                version = version + 1
            WHERE id = :menuItemId
            """, nativeQuery = true)
    int restoreStock(@Param("menuItemId") Long menuItemId,
                     @Param("quantity") int quantity);
}
