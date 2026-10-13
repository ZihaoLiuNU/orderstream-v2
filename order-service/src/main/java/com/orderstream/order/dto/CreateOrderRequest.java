package com.orderstream.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

/**
 * 下单请求。
 *
 * 【原版这个 DTO 上一个校验注解都没有，Controller 上也没有 @Valid】
 *   后果是实打实的：
 *     - quantity = -5 能下单，算出【负数总额】
 *     - items = [] 能创建空订单
 *     - restaurantId = null 一路走到数据库才 NPE 成 500
 *
 *   校验必须在【系统边界】做。让非法数据进入系统内部之后，
 *   之后每一层都要防御性地重新判断，而且总会有一层忘了判断。
 */
@Data
public class CreateOrderRequest {

    @NotNull(message = "餐厅 ID 不能为空")
    @Positive(message = "餐厅 ID 必须为正数")
    private Long restaurantId;

    @NotEmpty(message = "订单明细不能为空")
    // 上限防的是资源耗尽攻击：一个包含十万条明细的请求会让
    // menu-service 的 IN 查询爆炸、订单表插入十万行、事务持续几分钟。
    // 任何接受数组的接口都必须限长
    @Size(max = 50, message = "单笔订单最多 50 个菜品")
    /*
     * 【@Valid 必须加在这里 —— 嵌套校验不会自动生效】
     *
     * 只在 Controller 参数上写 @Valid，Spring 只会校验 CreateOrderRequest 本身的字段，
     * 【不会】递归进入 List<Item> 里校验每个 Item。
     * 要触发嵌套校验，必须在集合字段上显式加 @Valid。
     *
     * 这是 Bean Validation 最容易漏的一点，而且漏了完全没有提示 ——
     * Item 里的 @Positive 只是静默地不生效。
     */
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotNull(message = "菜品 ID 不能为空")
        @Positive
        private Long menuItemId;

        @NotNull(message = "数量不能为空")
        @Positive(message = "数量必须大于 0")
        @Max(value = 99, message = "单个菜品最多 99 份")
        private Integer quantity;
    }
}
