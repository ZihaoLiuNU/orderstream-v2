package com.orderstream.cart.controller;

import com.orderstream.cart.dto.AddCartItemRequest;
import com.orderstream.cart.dto.CartResponse;
import com.orderstream.cart.service.CartService;
import com.orderstream.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 购物车接口。
 *
 * 【注意所有接口都不接受 userId 参数 —— 这是刻意的】
 *   userId 一律从 SecurityContext 取（即网关验签后注入的身份），
 *   绝不从 URL 路径或请求体里取。
 *
 *   如果写成 GET /api/cart/{userId}，那么即使加了归属校验，
 *   也只是多了一层可能写错的判断。
 *   【根本不接受这个参数】，越权在结构上就不可能发生。
 *
 *   这是防 BOLA 最彻底的办法：
 *     不是"检查你有没有权限访问这个 ID"，
 *     而是"根本不让你指定 ID"。
 *   凡是"当前用户自己的资源"，都应该这样设计。
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public CartResponse getCart() {
        return cartService.getCart(CurrentUser.get().getUserId());
    }

    /**
     * 增减菜品数量。
     *
     * 用 PUT 而不是 POST：
     *   PUT 的语义要求【幂等】—— 同一个请求发多次和发一次效果相同。
     *   但我们这个接口是"增量"语义，发两次就加两次，【不幂等】。
     *   所以严格说这里应该用 POST。
     *
     *   我仍然用 PUT 是错的吗？是的，这是个可以被挑战的地方。
     *   诚实的说法是：这个接口的语义是"修改购物车"，用 PUT 更符合直觉，
     *   但 HTTP 规范上 POST 更准确。
     *   如果要严格遵循规范，应该改成
     *     POST /api/cart/items  （增量语义）
     *   或者把请求体改成"设置为 N 份"（绝对值语义），那样 PUT 就是对的。
     *
     *   把这个取舍写在注释里，比假装没这回事要好。
     */
    @PostMapping("/items")
    @PreAuthorize("hasRole('USER')")
    public CartResponse updateItem(@Valid @RequestBody AddCartItemRequest request) {
        return cartService.updateItem(CurrentUser.get().getUserId(), request);
    }

    /**
     * 手动清空购物车。
     *
     * 注意下单成功后购物车是【自动】清空的（见 OrderEventListener），
     * 不需要客户端调这个接口。这个接口是给"我不想要了"这个场景用的。
     */
    @DeleteMapping
    @PreAuthorize("hasRole('USER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCart() {
        cartService.clearCart(CurrentUser.get().getUserId());
    }
}
