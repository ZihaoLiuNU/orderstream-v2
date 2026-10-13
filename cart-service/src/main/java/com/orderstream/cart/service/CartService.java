package com.orderstream.cart.service;

import com.orderstream.cart.dto.AddCartItemRequest;
import com.orderstream.cart.dto.CartResponse;
import com.orderstream.common.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 购物车服务。存储在 Redis 里。
 *
 * 【Key 设计】
 *   cart:{userId}:restaurant  -> String，购物车归属的餐厅 ID
 *   cart:{userId}:items       -> Hash，field = menuItemId，value = 数量
 *
 * 为什么用 Hash 而不是把整个购物车序列化成一个 JSON String：
 *
 *   JSON String 方案：每次加一道菜都要"读出整个购物车 -> 反序列化 -> 修改 ->
 *   序列化 -> 写回"。这是个 read-modify-write，在并发下会丢更新
 *   （用户在手机和网页同时操作，或者手抖快速点了两下）。
 *   要修就得加分布式锁或用 WATCH/MULTI，复杂度陡增。
 *
 *   Hash 方案：HINCRBY 是【服务端原子操作】，
 *   "把 field 的值加 1"整个过程在 Redis 单线程里一次完成，
 *   根本不存在竞态。不需要锁、不需要事务、不需要重试。
 *
 *   【这是"选对数据结构就消灭了并发问题"的典型例子】。
 *   很多并发 bug 的根源不是没加锁，而是把一个本可以原子完成的操作
 *   拆成了读和写两步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final StringRedisTemplate redis;

    /**
     * 购物车过期时间。
     *
     * 【为什么必须有 TTL】
     *   行业数据显示约 70% 的购物车会被放弃。没有 TTL 的话，
     *   Redis 里会无限堆积永远不会变成订单的数据，
     *   最终内存耗尽 —— 而 Redis 内存满了之后的行为
     *   （取决于 maxmemory-policy）可能是拒绝写入，整个服务不可用。
     *
     * 【7 天怎么来的】
     *   短了（比如 1 小时）：用户中午加购、晚上想下单时购物车空了，体验很差。
     *   长了（比如 90 天）：内存浪费，而且三个月前的购物车里的菜
     *   很可能已经下架或改价，恢复出来反而是负担。
     *   7 天覆盖了"这周想吃"这个典型的用户心智周期。
     */
    @Value("${orderstream.cart.ttl:P7D}")
    private Duration cartTtl;

    private static final String RESTAURANT_KEY_SUFFIX = ":restaurant";
    private static final String ITEMS_KEY_SUFFIX = ":items";

    // ======================================================================
    // 读
    // ======================================================================

    public CartResponse getCart(Long userId) {
        String restaurantKey = restaurantKey(userId);
        String itemsKey = itemsKey(userId);

        String restaurantId = redis.opsForValue().get(restaurantKey);
        if (restaurantId == null) {
            // 空购物车。返回一个空对象而不是 404 ——
            // "购物车是空的"是完全正常的状态，不是"资源不存在"。
            // 返回 404 会逼着客户端写一堆特判代码
            return CartResponse.builder()
                    .items(List.of())
                    .totalQuantity(0)
                    .build();
        }

        Map<Object, Object> raw = redis.opsForHash().entries(itemsKey);

        List<CartResponse.Item> items = new ArrayList<>(raw.size());
        int total = 0;
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            int qty = Integer.parseInt(entry.getValue().toString());
            if (qty <= 0) continue;   // 防御：理论上不会有，删除逻辑已经处理了
            items.add(CartResponse.Item.builder()
                    .menuItemId(Long.valueOf(entry.getKey().toString()))
                    .quantity(qty)
                    .build());
            total += qty;
        }

        return CartResponse.builder()
                .restaurantId(Long.valueOf(restaurantId))
                .items(items)
                .totalQuantity(total)
                .build();
    }

    // ======================================================================
    // 写
    // ======================================================================

    /**
     * 增减购物车中某个菜品的数量。
     */
    public CartResponse updateItem(Long userId, AddCartItemRequest request) {
        String restaurantKey = restaurantKey(userId);
        String itemsKey = itemsKey(userId);

        String currentRestaurant = redis.opsForValue().get(restaurantKey);

        /*
         * 【切换餐厅时清空购物车】
         *
         * 外卖场景下一个订单只能来自一家店（配送方式决定的）。
         * 用户在 A 店加了菜，又去 B 店加菜时，必须做个选择。
         *
         * 真实 App（美团、DoorDash）的做法是弹窗问用户
         * "切换餐厅将清空购物车，是否继续？"。
         * 后端这里直接清空是把决定权交给了前端 ——
         * 前端应该先弹窗确认再调这个接口。
         *
         * 【更严谨的做法】是后端返回一个 409 Conflict + 明确的错误码，
         * 让前端必须显式带上 force=true 参数才执行清空。
         * 这样"清空购物车"这个破坏性操作就不会被意外触发。
         * 我这里为了保持接口简单没这么做，但这个取舍要说得出来：
         * 凡是【有破坏性且不可撤销】的副作用，都应该要求调用方显式确认。
         */
        if (currentRestaurant != null && !currentRestaurant.equals(String.valueOf(request.getRestaurantId()))) {
            log.info("[购物车] 切换餐厅，清空原购物车 userId={} {} -> {}",
                    userId, currentRestaurant, request.getRestaurantId());
            redis.delete(List.of(restaurantKey, itemsKey));
        }

        if (request.getDelta() == 0) {
            throw new BusinessException("数量变化不能为 0");
        }

        /*
         * HINCRBY：Redis 服务端的原子自增。
         *
         * 它返回自增【之后】的值，所以我们一次往返就同时完成了
         * "修改"和"知道结果"两件事 —— 不需要再查一次。
         *
         * 对比 read-modify-write：
         *   两次往返(GET + SET)，中间有竞态窗口，还要处理并发冲突。
         * 原子操作把三个问题（竞态、往返次数、复杂度）一起解决了。
         */
        Long newQty = redis.opsForHash().increment(itemsKey, String.valueOf(request.getMenuItemId()),
                request.getDelta());

        if (newQty != null && newQty <= 0) {
            // 减到 0 或负数就移除这个 field。
            // 保留一个 quantity=0 的条目是没有意义的，而且会让
            // "购物车是不是空的"这个判断变复杂
            redis.opsForHash().delete(itemsKey, String.valueOf(request.getMenuItemId()));
        }

        // 记录/刷新餐厅归属
        redis.opsForValue().set(restaurantKey, String.valueOf(request.getRestaurantId()));

        /*
         * 【每次操作都重设 TTL —— 这是"滑动过期"】
         *
         * 如果只在创建时设一次 TTL，那么用户第 6 天还在往购物车里加菜，
         * 第 7 天购物车照样过期 —— 明明是个活跃购物车却被清掉了。
         *
         * 每次写操作都刷新过期时间，语义就变成了
         * "7 天【没有任何操作】才过期"，这才符合直觉。
         *
         * 注意两个 key 都要刷新。只刷新一个的话，
         * 会出现 items 还在但 restaurant 过期了（或反之）的半残状态 ——
         * 这类"关联的多个 key 过期时间不一致"是 Redis 使用中很常见的 bug。
         * 更彻底的解法是把所有数据放进【一个】 key，让原子性由 Redis 保证。
         */
        redis.expire(restaurantKey, cartTtl);
        redis.expire(itemsKey, cartTtl);

        return getCart(userId);
    }

    /**
     * 清空购物车。
     *
     * 【这个操作天然幂等 —— 所以消费 Kafka 事件时不需要去重表】
     *   DELETE 一个不存在的 key，Redis 返回 0，不报错。
     *   执行一次和执行十次，结果完全相同（购物车是空的）。
     *
     *   对比 menu-service 的"扣库存"：那是累加操作，
     *   执行两次就多扣一份，必须有 ProcessedEvent 表去重。
     *
     *   【判断标准：f(f(x)) == f(x) 是否成立】。
     *   成立就不需要额外的幂等机制，不成立就必须有。
     *   这个判断要逐个操作做，不能一刀切。
     */
    public void clearCart(Long userId) {
        Long deleted = redis.delete(List.of(restaurantKey(userId), itemsKey(userId)));
        if (deleted != null && deleted > 0) {
            log.info("[购物车] 已清空 userId={}", userId);
        }
    }

    // ======================================================================

    /**
     * Key 命名带上业务前缀。
     *
     * 【为什么不直接用 userId 做 key】
     *   Redis 没有"数据库表"的概念，所有 key 在一个扁平的命名空间里。
     *   如果多个服务共用一个 Redis 实例（很常见），
     *   "123" 这样的 key 随时可能被另一个服务覆盖。
     *
     *   带上 "cart:" 前缀之后：
     *     1) 不会撞车
     *     2) 可以用 SCAN cart:* 按业务批量查看/清理
     *     3) 在 redis-cli 里一眼看得出这是什么数据
     *
     *   生产环境还应该带上应用名和环境（"orderstream:prod:cart:..."），
     *   防止测试环境误连生产 Redis 时污染数据 —— 这事真实发生过很多次。
     */
    private String restaurantKey(Long userId) {
        return "cart:" + userId + RESTAURANT_KEY_SUFFIX;
    }

    private String itemsKey(Long userId) {
        return "cart:" + userId + ITEMS_KEY_SUFFIX;
    }
}
