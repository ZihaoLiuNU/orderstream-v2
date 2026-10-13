package com.orderstream.menu.controller;

import com.orderstream.menu.dto.*;
import com.orderstream.menu.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final MenuService menuService;

    /**
     * 餐厅列表。公开接口（网关白名单里只放行了 GET）。
     *
     * @PageableDefault 的 size=20 是【必须】显式设的：
     *   Spring Data 的默认页大小也是 20，但默认【最大】页大小是 2000。
     *   客户端传 ?size=2000 就能一次拉 2000 条。
     *   更狠的是配置 spring.data.web.pageable.max-page-size 才能真正封顶。
     *   任何分页接口都要检查这个上限，否则分页等于没分。
     */
    @GetMapping
    public Page<RestaurantResponse> list(
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return menuService.listRestaurants(pageable);
    }

    /** 餐厅详情 + 菜单。走 Redis 缓存 */
    @GetMapping("/{id}")
    public RestaurantResponse detail(@PathVariable Long id) {
        return menuService.getRestaurantWithMenu(id);
    }

    /**
     * 创建餐厅 —— 仅管理员。
     *
     * 【纵深防御的第二层】
     *   网关已经按路径规则挡了一道（admin-only-paths），这里再挡一道。
     *   看似重复，但两者失效的方式不同：
     *     网关规则是按 URL 写的，重构改路径就静默失效；
     *     这个注解跟着方法走，方法改名换路径它都还在。
     *   安全控制的冗余是【必要的】，不是浪费。
     *
     *   身份来源是网关注入的 X-User-Role 头，
     *   由 common 里的 IdentityHeaderAuthenticationFilter 转成 SecurityContext。
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantResponse create(@Valid @RequestBody CreateRestaurantRequest request) {
        return menuService.createRestaurant(request);
    }

    @PostMapping("/{id}/menu-items")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItemResponse addMenuItem(@PathVariable Long id,
                                        @Valid @RequestBody CreateMenuItemRequest request) {
        return menuService.addMenuItem(id, request);
    }
}
