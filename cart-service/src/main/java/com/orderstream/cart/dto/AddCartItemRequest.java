package com.orderstream.cart.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AddCartItemRequest {

    @NotNull(message = "餐厅 ID 不能为空")
    @Positive
    private Long restaurantId;

    @NotNull(message = "菜品 ID 不能为空")
    @Positive
    private Long menuItemId;

    /**
     * 增量，可以是负数（表示减少）。
     *
     * 用"增量"而不是"设置为某个值"的语义，是为了配合 Redis 的 HINCRBY ——
     * 它是【原子自增】，不需要"读出来-加一-写回去"这个有竞态的三步操作。
     *
     * 真实场景：用户在手机和网页同时加购，两个请求几乎同时到达。
     *   读-改-写模型：都读到 1，都写 2，最终是 2（丢了一次点击）
     *   HINCRBY 模型：1 -> 2 -> 3，正确
     *
     * 【把操作设计成"可交换的增量"而不是"赋值"，是并发安全的一个通用技巧】。
     * 它让操作天然可并行、可乱序，不需要加锁。
     * CRDT（无冲突复制数据类型）的核心思想就是这个。
     */
    @NotNull(message = "数量变化不能为空")
    @Min(value = -99, message = "单次变化量不能小于 -99")
    @Max(value = 99,  message = "单次变化量不能大于 99")
    private Integer delta;
}
