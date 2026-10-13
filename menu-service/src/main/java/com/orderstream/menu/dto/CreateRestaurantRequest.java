package com.orderstream.menu.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateRestaurantRequest {
    @NotBlank @Size(max = 200) private String name;
    @NotBlank @Size(max = 500) private String address;
    @Size(max = 2000)          private String description;
}
