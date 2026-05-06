package com.orderstream.config;

import com.orderstream.entity.MenuItem;
import com.orderstream.entity.Restaurant;
import com.orderstream.entity.User;
import com.orderstream.repository.MenuItemRepository;
import com.orderstream.repository.RestaurantRepository;
import com.orderstream.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        initRestaurantData();
        initUserData();
    }

    private void initRestaurantData() {
        if (restaurantRepository.count() > 0) return;

        Restaurant restaurant = restaurantRepository.save(
                Restaurant.builder()
                        .name("Test Restaurant")
                        .address("123 Main St")
                        .description("A test restaurant")
                        .build()
        );

        menuItemRepository.save(MenuItem.builder()
                .name("Burger").description("Classic beef burger")
                .price(new BigDecimal("9.99")).restaurant(restaurant).build());

        menuItemRepository.save(MenuItem.builder()
                .name("Pizza").description("Margherita pizza")
                .price(new BigDecimal("12.99")).restaurant(restaurant).build());

        menuItemRepository.save(MenuItem.builder()
                .name("Fries").description("Crispy french fries")
                .price(new BigDecimal("4.99")).restaurant(restaurant).build());

        log.info("餐厅测试数据初始化完成：1 家餐厅，3 个菜品");
    }

    private void initUserData() {
        if (userRepository.count() > 0) return;

        // 创建管理员账号（代表餐厅后台，有权更新订单状态）
        userRepository.save(User.builder()
                .username("admin")
                .email("admin@orderstream.com")
                .password(passwordEncoder.encode("admin123"))
                .role(User.Role.ADMIN)
                .build());

        // 批量创建 1000 个模拟用户，支撑 RBAC 和高并发下单场景的演示
        String encodedPassword = passwordEncoder.encode("password123");
        List<User> users = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            users.add(User.builder()
                    .username("user" + i)
                    .email("user" + i + "@orderstream.com")
                    .password(encodedPassword)
                    .role(User.Role.USER)
                    .build());
        }
        userRepository.saveAll(users);

        log.info("用户数据初始化完成：1 个管理员 + 1000 个模拟用户");
    }
}
