package com.orderstream.config;

import com.orderstream.entity.MenuItem;
import com.orderstream.entity.Restaurant;
import com.orderstream.repository.MenuItemRepository;
import com.orderstream.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    @Override
    public void run(String... args) {
        if (restaurantRepository.count() > 0) {
            return;
        }

        Restaurant restaurant = restaurantRepository.save(
                Restaurant.builder()
                        .name("Test Restaurant")
                        .address("123 Main St")
                        .description("A test restaurant")
                        .build()
        );

        menuItemRepository.save(MenuItem.builder()
                .name("Burger")
                .description("Classic beef burger")
                .price(new BigDecimal("9.99"))
                .restaurant(restaurant)
                .build());

        menuItemRepository.save(MenuItem.builder()
                .name("Pizza")
                .description("Margherita pizza")
                .price(new BigDecimal("12.99"))
                .restaurant(restaurant)
                .build());

        menuItemRepository.save(MenuItem.builder()
                .name("Fries")
                .description("Crispy french fries")
                .price(new BigDecimal("4.99"))
                .restaurant(restaurant)
                .build());

        System.out.println("Test data initialized: 1 restaurant, 3 menu items");
    }
}
