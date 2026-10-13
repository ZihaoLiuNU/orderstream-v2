package com.orderstream.menu.config;

import com.orderstream.menu.entity.MenuItem;
import com.orderstream.menu.entity.Restaurant;
import com.orderstream.menu.repository.MenuItemRepository;
import com.orderstream.menu.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 压测用种子数据。
 *
 * 两道保险（@Profile + @ConditionalOnProperty）同 auth-service，
 * 保证生产环境绝不会塞进测试数据。
 */
@Slf4j
@Configuration
@Profile("!prod")
@ConditionalOnProperty(name = "orderstream.seed-data.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevDataInitializer {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    @Bean
    public ApplicationRunner seedMenu() {
        return args -> {
            if (restaurantRepository.count() > 0) {
                log.info("[种子数据] 餐厅表非空，跳过初始化");
                return;
            }

            List<MenuItem> allItems = new ArrayList<>();

            for (int r = 1; r <= 5; r++) {
                Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                        .name("测试餐厅 " + r)
                        .address(r + " Main Street")
                        .description("压测用餐厅")
                        .build());

                for (int m = 1; m <= 10; m++) {
                    allItems.add(MenuItem.builder()
                            .restaurantId(restaurant.getId())
                            .name(String.format("菜品 R%d-M%d", r, m))
                            .description("压测用菜品")
                            // 用字符串构造 BigDecimal。
                            // new BigDecimal(9.99) 传 double 会把二进制浮点误差带进来，
                            // 结果是 9.9900000000000002131628207280300557613372802734375
                            .price(new BigDecimal(String.format("%d.99", 5 + m)))
                            /*
                             * 库存给到 100 万。
                             *
                             * 【这是为压测服务的刻意设置，必须解释清楚】
                             *   压测要测的是"系统在高并发下的吞吐和正确性"，
                             *   不是"库存卖光后的拒绝率"。
                             *   如果库存只有 100 份，压测跑到第 101 单就全部失败了，
                             *   测出来的是补偿路径的性能，不是主路径的。
                             *
                             *   想专门测超卖防护时，会另建一个库存只有 10 份的菜品，
                             *   用 500 并发去抢，验证最终恰好卖出 10 份、不多不少。
                             *   那是另一个测试场景（见 loadtest/ 下的说明）。
                             */
                            .stockQuantity(1_000_000)
                            .build());
                }
            }

            menuItemRepository.saveAll(allItems);
            log.info("[种子数据] 已创建 5 家餐厅 + {} 个菜品（库存各 100 万，供压测使用）", allItems.size());
        };
    }
}
