package com.orderstream.menu.service;

import com.orderstream.common.web.BusinessException;
import com.orderstream.common.web.ResourceNotFoundException;
import com.orderstream.menu.dto.*;
import com.orderstream.menu.entity.MenuItem;
import com.orderstream.menu.entity.Restaurant;
import com.orderstream.menu.repository.MenuItemRepository;
import com.orderstream.menu.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 菜单查询与维护。
 *
 * 这是整个系统里唯一适合上缓存的地方，原因值得说清楚：
 *   菜单是【读多写极少】的数据 —— 用户浏览量巨大，但餐厅一周才改一次菜单。
 *   读写比可能是 10000:1。这种数据不缓存是浪费。
 *
 * 反过来，订单和库存【绝对不能】这样缓存：
 *   它们读写比接近 1:1，而且对一致性要求极高。
 *   缓存一个每次读都可能已经过期的库存数，只会制造 bug 而不会提升性能。
 *
 * 判断要不要缓存的两个问题：
 *   1) 读写比是否足够悬殊？（不悬殊就没收益）
 *   2) 能否容忍一段时间的陈旧数据？（不能容忍就别缓存）
 * 两个都是"是"才缓存。很多性能问题正是盲目加缓存造成的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 餐厅列表。
     *
     * 【故意不缓存】—— 分页列表缓存的收益很差：
     *   缓存 key 要包含 page/size/sort，组合数量爆炸，命中率极低，
     *   而且任何一家餐厅变更都要失效所有页的缓存，几乎不可能做对。
     *   分页列表更适合用数据库索引优化，而不是缓存。
     */
    @Transactional(readOnly = true)
    public Page<RestaurantResponse> listRestaurants(Pageable pageable) {
        return restaurantRepository.findByActiveTrue(pageable)
                .map(RestaurantResponse::summary);
    }

    /**
     * 餐厅详情 + 菜单。这是缓存的主要目标。
     *
     * ========================================================================
     * 【Cache-Aside 模式（旁路缓存）】
     * ========================================================================
     * @Cacheable 的行为是：
     *   1. 先查 Redis，命中就直接返回，方法体根本不执行
     *   2. 未命中则执行方法体（查数据库），把结果写入 Redis，再返回
     *
     * 这就是 cache-aside —— 应用代码负责在旁边维护缓存，
     * 缓存本身不知道数据库的存在。
     *
     * 对比 read-through/write-through（缓存层自己负责回源）：
     *   cache-aside 更灵活也更常用，代价是缓存一致性要应用自己管。
     *
     * ========================================================================
     * 【关键：Redis 挂了会发生什么】
     * ========================================================================
     * Spring Cache 默认行为是：Redis 报错 -> 异常向上抛 -> 请求失败。
     * 这意味着【缓存挂了整个服务就挂了】—— 本来是加速用的组件变成了单点故障。
     *
     * 我在 RedisCacheConfig 里配置了 CacheErrorHandler 把异常吞掉并降级到数据库。
     * 这是个重要的架构原则：
     *   缓存是【可选的优化】，不是【必需的依赖】。
     *   缓存不可用时，系统应该变慢，而不是不可用。
     *
     * 这和 README 里写的 "Redis 故障只影响读性能，不会损坏订单数据"
     * 是同一个思路：Postgres 是唯一的事实来源(system of record)。
     */
    @Cacheable(value = "restaurant-menu", key = "#restaurantId")
    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurantWithMenu(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .filter(Restaurant::getActive)
                .orElseThrow(() -> new ResourceNotFoundException("餐厅不存在: " + restaurantId));

        List<MenuItemResponse> items = menuItemRepository
                .findByRestaurantIdAndAvailableTrueOrderByName(restaurantId)
                .stream()
                .map(MenuItemResponse::from)
                .toList();

        log.debug("[菜单] 回源数据库 restaurantId={} 菜品数={}", restaurantId, items.size());
        return RestaurantResponse.withMenu(restaurant, items);
    }

    /**
     * 批量查菜品 —— 供 order-service 下单时调用的内部接口。
     *
     * 【这个接口故意不缓存】
     *   下单时读到的价格会被写进订单，成为一笔交易的依据。
     *   如果读到的是缓存里几分钟前的旧价格，用户就会按错误的价格下单 ——
     *   这是真金白银的问题，不是显示问题。
     *
     *   浏览菜单可以看到稍微过期的价格（无所谓），
     *   但下单【必须】读最新的。同一份数据，不同场景下对一致性的要求完全不同，
     *   这正是缓存策略必须按【用途】而不是按【表】来设计的原因。
     */
    @Transactional(readOnly = true)
    public List<MenuItemResponse> getMenuItemsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // 防御性上限：防止有人构造一个包含十万个 ID 的请求打垮数据库。
        // 任何接受"数组"作为输入的接口都必须限制长度，这是 API 设计的基本功
        if (ids.size() > 200) {
            throw new BusinessException("单次最多查询 200 个菜品");
        }
        return menuItemRepository.findByIdIn(ids).stream()
                .map(MenuItemResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------
    // 维护（管理员）
    // ------------------------------------------------------------------

    @Transactional
    public RestaurantResponse createRestaurant(CreateRestaurantRequest request) {
        Restaurant saved = restaurantRepository.save(Restaurant.builder()
                .name(request.getName())
                .address(request.getAddress())
                .description(request.getDescription())
                .build());
        log.info("[菜单] 创建餐厅 id={} name={}", saved.getId(), saved.getName());
        return RestaurantResponse.summary(saved);
    }

    /**
     * 新增菜品。
     *
     * @CacheEvict 在写操作后【删除】缓存，而不是【更新】缓存。
     *
     * 【为什么是删除而不是更新 —— 这是缓存一致性的核心问题】
     *   更新缓存(write-through)看起来更高效（省一次回源），但存在竞态：
     *     线程A 更新数据库为 v1
     *     线程B 更新数据库为 v2
     *     线程B 更新缓存为 v2
     *     线程A 更新缓存为 v1     <- 缓存里是 v1，数据库里是 v2，永久不一致！
     *   两个写线程的"写数据库"和"写缓存"顺序可能交错，谁也保证不了。
     *
     *   删除缓存则不会：无论删除的顺序如何，结果都是"缓存为空"，
     *   下次读必然回源拿到最新值。最坏情况只是多一次回源，不会不一致。
     *
     *   这就是业界所说的 Cache-Aside 标准做法：【更新数据库 + 删除缓存】。
     *
     * 【它仍然不是完美的 —— 要能说出残留的问题】
     *   极端竞态：读线程未命中 -> 查到旧值 -> (此时写线程更新DB并删缓存) -> 读线程把旧值写入缓存
     *   缓存里留下了旧值。这个窗口极窄（要求读的回源比写的整个流程还慢），
     *   但理论上存在。彻底解决要靠"延迟双删"或订阅 binlog 失效缓存。
     *   对菜单数据，我用 TTL(10分钟) 兜底就够了 —— 最坏情况下陈旧 10 分钟，可接受。
     */
    @CacheEvict(value = "restaurant-menu", key = "#restaurantId")
    @Transactional
    public MenuItemResponse addMenuItem(Long restaurantId, CreateMenuItemRequest request) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("餐厅不存在: " + restaurantId);
        }
        MenuItem saved = menuItemRepository.save(MenuItem.builder()
                .restaurantId(restaurantId)
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .stockQuantity(request.getStockQuantity())
                .build());
        log.info("[菜单] 新增菜品 id={} restaurantId={} name={}", saved.getId(), restaurantId, saved.getName());
        return MenuItemResponse.from(saved);
    }
}
