package com.orderstream.controller;

import com.orderstream.dto.RestaurantResponse;
import com.orderstream.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    // 所有登录用户可浏览餐厅列表
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @GetMapping
    public ResponseEntity<List<RestaurantResponse>> getAllRestaurants() {
        return ResponseEntity.ok(restaurantService.getAllRestaurants());
    }

    // 所有登录用户可查看某家餐厅的菜单详情
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<RestaurantResponse> getRestaurantWithMenu(@PathVariable Long id) {
        return ResponseEntity.ok(restaurantService.getRestaurantWithMenu(id));
    }
}
