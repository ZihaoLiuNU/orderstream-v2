package com.orderstream.order.dto;

import com.orderstream.order.entity.Order;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateOrderStatusRequest {

    /**
     * 目标状态。
     *
     * 用枚举类型而不是 String 的好处：
     *   Jackson 在反序列化时就会拒绝非法值，返回 400 而不是让它走到业务层。
     *   传 "BANANA" 会得到一个明确的反序列化错误，
     *   而不是在 Service 里 valueOf 抛 IllegalArgumentException 变成 500。
     *
     * 【类型系统是最廉价的校验】—— 能用类型表达的约束，就不要用运行时判断。
     */
    @NotNull(message = "目标状态不能为空")
    private Order.OrderStatus status;
}
