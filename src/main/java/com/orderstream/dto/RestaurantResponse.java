package com.orderstream.dto;

import com.orderstream.entity.Restaurant;
import lombok.Data;

import java.util.List;

@Data
public class RestaurantResponse {
    private Long id;
    private String name;
    private String address;
    private String description;
    private List<MenuItemResponse> menuItems;

    public static RestaurantResponse from(Restaurant restaurant, List<MenuItemResponse> menuItems) {
        RestaurantResponse response = new RestaurantResponse();
        response.setId(restaurant.getId());
        response.setName(restaurant.getName());
        response.setAddress(restaurant.getAddress());
        response.setDescription(restaurant.getDescription());
        response.setMenuItems(menuItems);
        return response;
    }

    public static RestaurantResponse from(Restaurant restaurant) {
        return from(restaurant, null);
    }
}
