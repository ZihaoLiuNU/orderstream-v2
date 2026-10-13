package com.orderstream.order.client;

import java.math.BigDecimal;

/**
 * menu-service 返回的菜品视图（order-service 侧的本地表示）。
 *
 * 【为什么要在这边单独定义一个 record，而不是把 menu-service 的 DTO 放进 common 共享】
 *
 *   这是微服务设计里一个很重要但容易做错的决定。
 *
 *   把 MenuItemResponse 放进 common 让两边共用，看起来省事，实际上是【反模式】：
 *     - menu-service 给 DTO 加一个字段（比如 calories），order-service 被迫重新编译部署
 *     - order-service 想改字段名以适配自己的领域语言，会影响 menu-service
 *     - 两个服务的发布节奏被 DTO 绑死了，"独立部署"这个微服务的核心收益就没了
 *
 *   正确做法是【每个服务定义自己需要的视图】，只包含它真正用到的字段。
 *   这叫 Consumer-Driven Contract（消费者驱动契约）：
 *   契约由消费者的需求定义，而不是由生产者的内部模型定义。
 *
 *   注意这和 common 里的【事件】不同 —— 事件是异步广播的契约，
 *   必须有一份权威定义；而 REST 响应是点对点的，各取所需更健康。
 *
 * 用 record 而不是 class：
 *   它是纯粹的不可变数据载体，record 自动生成构造器、equals、hashCode、toString，
 *   而且【不可变】这一点很重要 —— 从外部服务拿到的数据不该被本地代码随意修改。
 */
public record MenuItemView(
        Long id,
        Long restaurantId,
        String name,
        BigDecimal price,
        Boolean inStock
) {}
