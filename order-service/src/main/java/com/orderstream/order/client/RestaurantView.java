package com.orderstream.order.client;

/**
 * menu-service 返回的餐厅视图。
 *
 * 只取 order-service 真正需要的 3 个字段。
 * menu-service 的响应里还有 description、menuItems 等，
 * Jackson 默认会忽略 JSON 里多出来的字段（FAIL_ON_UNKNOWN_PROPERTIES 默认 false），
 * 所以生产方加字段不会导致消费方反序列化失败。
 *
 * 这个"宽进严出"的特性是跨服务兼容性的基础，也叫 Postel 法则
 * （对自己发出的东西严格，对收到的东西宽容）：
 *   生产者【只能加字段，不能删字段或改语义】，消费者【忽略不认识的字段】。
 *   两边都遵守，服务就可以独立演进而不互相破坏。
 */
public record RestaurantView(
        Long id,
        String name,
        String address
) {}
