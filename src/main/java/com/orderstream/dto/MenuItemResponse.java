package com.orderstream.dto;

import com.orderstream.entity.MenuItem;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MenuItemResponse {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;

    public static MenuItemResponse from(MenuItem item) {
        MenuItemResponse response = new MenuItemResponse();
        response.setId(item.getId());
        response.setName(item.getName());
        response.setDescription(item.getDescription());
        response.setPrice(item.getPrice());
        return response;
    }
}
