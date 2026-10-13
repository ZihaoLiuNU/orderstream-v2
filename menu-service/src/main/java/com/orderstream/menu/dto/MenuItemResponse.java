package com.orderstream.menu.dto;

import com.orderstream.menu.entity.MenuItem;
import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 菜品的对外表示。
 *
 * 实现 Serializable 是因为它会被存进 Redis 缓存。
 * 注意：我用的是 JSON 序列化(GenericJackson2JsonRedisSerializer)而不是 JDK 序列化，
 * 但保留 Serializable 是为了防止将来有人切换序列化方式时踩坑。
 *
 * 【为什么不直接缓存 MenuItem 实体】
 *   JPA 实体带有 Hibernate 的代理和持久化上下文状态，序列化进缓存后
 *   取出来是个"游离对象"，行为怪异（比如懒加载字段会抛异常）。
 *   缓存里永远只放纯粹的数据传输对象。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MenuItemResponse implements Serializable {

    private Long id;
    private Long restaurantId;
    private String name;
    private String description;
    private BigDecimal price;

    /**
     * 只暴露"有没有货"，不暴露具体剩余数量。
     *
     * 这是有意的信息隐藏：
     *   - 业务上：精确库存是商业机密，竞争对手能据此推算出你的销量
     *   - 技术上：库存变化极快，返回一个精确数字会让客户端产生
     *     "显示还有 3 份但下单失败"的困惑。返回布尔值反而更诚实 ——
     *     因为在分布式系统里，你读到的任何库存数字在返回给用户的那一刻就已经过期了
     */
    private Boolean inStock;

    public static MenuItemResponse from(MenuItem item) {
        return MenuItemResponse.builder()
                .id(item.getId())
                .restaurantId(item.getRestaurantId())
                .name(item.getName())
                .description(item.getDescription())
                .price(item.getPrice())
                .inStock(item.getAvailable() && item.getStockQuantity() > 0)
                .build();
    }
}
