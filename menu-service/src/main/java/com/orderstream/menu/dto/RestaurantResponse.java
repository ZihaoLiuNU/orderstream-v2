package com.orderstream.menu.dto;

import com.orderstream.menu.entity.Restaurant;
import lombok.*;
import java.io.Serializable;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RestaurantResponse implements Serializable {

    private Long id;
    private String name;
    private String address;
    private String description;

    /**
     * 可选字段：列表接口不返回菜单（null），详情接口才返回。
     *
     * 为什么不拆成两个 DTO：字段完全重叠，拆开会造成大量重复代码。
     * 为什么不总是返回菜单：列表页如果每家餐厅都带上全部菜品，
     * 响应体会膨胀几十倍，而且列表页根本用不到 —— 这是典型的过度获取(over-fetching)。
     * GraphQL 就是为了解决这类问题而生的，但对一个 REST API，
     * 用"详情接口才返回关联数据"这个简单约定就够了。
     */
    private List<MenuItemResponse> menuItems;

    public static RestaurantResponse summary(Restaurant r) {
        return RestaurantResponse.builder()
                .id(r.getId()).name(r.getName())
                .address(r.getAddress()).description(r.getDescription())
                .build();
    }

    public static RestaurantResponse withMenu(Restaurant r, List<MenuItemResponse> items) {
        RestaurantResponse resp = summary(r);
        resp.setMenuItems(items);
        return resp;
    }
}
