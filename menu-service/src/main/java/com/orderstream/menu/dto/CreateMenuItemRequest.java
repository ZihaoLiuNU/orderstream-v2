package com.orderstream.menu.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class CreateMenuItemRequest {

    @NotBlank @Size(max = 200)
    private String name;

    @Size(max = 2000)
    private String description;

    @NotNull(message = "价格不能为空")
    // @DecimalMin 而不是 @Min：@Min 只支持整数类型，对 BigDecimal 会在启动时报错
    @DecimalMin(value = "0.00", message = "价格不能为负数")
    // digits 限制总位数和小数位，和数据库的 NUMERIC(10,2) 对齐。
    // 校验规则和数据库约束保持一致很重要：否则会出现"应用层放过了、数据库拒绝了"
    // 的 500 错误，而不是清晰的 400
    @Digits(integer = 8, fraction = 2, message = "价格最多 8 位整数和 2 位小数")
    private BigDecimal price;

    @NotNull
    @Min(value = 0, message = "库存不能为负数")
    private Integer stockQuantity;
}
