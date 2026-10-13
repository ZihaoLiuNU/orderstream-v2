package com.orderstream.cart.dto;

import lombok.*;
import java.util.List;

/**
 * 购物车内容。
 *
 * ============================================================================
 * 【刻意不存价格 —— 这是个重要的设计决策】
 * ============================================================================
 *
 * 很自然的做法是在加购时把价格一起存进购物车，这样展示时不用再查菜单。
 * 但这会制造一个【第二事实来源】，带来三个问题：
 *
 *   1) 价格会过期：用户昨天加购，今天餐厅涨价了。
 *      购物车显示 9.99，下单时按 12.99 扣款 —— 用户会认为这是欺诈。
 *
 *   2) 可被篡改：如果价格由客户端在加购请求里提交，
 *      攻击者直接改成 0.01。任何由客户端提供的价格都是不可信的。
 *
 *   3) 需要同步：餐厅改价时要去更新所有人的购物车，这是个分布式一致性噩梦。
 *
 * 正确做法：购物车只存"用户想要什么"（菜品 ID + 数量），
 * 价格在【下单那一刻】由 order-service 向 menu-service 实时查询，
 * 并在那一刻快照进订单（见 OrderItem 的注释）。
 *
 * 【一句话概括】：购物车存的是【意图】，订单存的是【事实】。
 * 意图不需要价格，事实必须锁定价格。
 *
 * 展示时客户端（或 BFF 层）再去查一次菜单拿价格即可 ——
 * 多一次查询，换来的是"永远不会显示错误价格"。
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CartResponse {

    /**
     * 购物车归属的餐厅。
     *
     * 一个购物车只能属于一家餐厅 —— 这是外卖业务的常识约束
     * （不可能一单同时点两家店的菜，因为配送方式不同）。
     * 加购不同餐厅的菜时会清空原有购物车，见 CartService.addItem。
     */
    private Long restaurantId;

    private List<Item> items;

    /** 便于客户端直接展示角标数字 */
    private int totalQuantity;

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Item {
        private Long menuItemId;
        private Integer quantity;
    }
}
