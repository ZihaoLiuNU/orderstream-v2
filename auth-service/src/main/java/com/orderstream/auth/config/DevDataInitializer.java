package com.orderstream.auth.config;

import com.orderstream.auth.entity.User;
import com.orderstream.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * 开发/压测环境的种子数据。
 *
 * 【两道保险，缺一不可】—— 原版代码一道都没有，意味着它在生产环境启动时
 * 也会往库里塞一堆测试账号，而且密码是人人皆知的 "password123"。
 * 这不是小问题：生产库里有 1000 个弱密码账号，等同于后门。
 *
 *   保险 1：@Profile("!prod") —— prod profile 下这个类根本不会被实例化
 *   保险 2：@ConditionalOnProperty —— 还要显式开启开关才生效
 *
 * 为什么要两道：单靠 profile 的话，有人忘了设 SPRING_PROFILES_ACTIVE=prod 就完蛋了。
 * 安全配置的默认值必须是「安全的那一边」(secure by default)。
 */
@Slf4j
@Configuration
@Profile("!prod")
@ConditionalOnProperty(name = "orderstream.seed-data.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevDataInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 压测需要大量账号，可通过配置调整 */
    @Value("${orderstream.seed-data.user-count:1000}")
    private int userCount;

    @Bean
    public ApplicationRunner seedUsers() {
        return args -> {
            // 幂等：已经初始化过就跳过。容器重启不会重复插入
            if (userRepository.count() > 0) {
                log.info("[种子数据] 用户表非空，跳过初始化");
                return;
            }

            /*
             * 关键性能细节：BCrypt 哈希【只算一次】，然后复用。
             *
             * 如果在循环里对每个用户单独调 passwordEncoder.encode()，
             * cost=12 意味着每次约 250ms，1000 个用户就是 250 秒 —— 启动直接超时。
             *
             * 复用哈希在【生产】上是严重错误（所有用户同 salt，撞库一次全军覆没），
             * 但这里是压测账号，全部用同一个密码本身就是前提。
             * 这个取舍必须写在注释里，否则将来有人照抄到生产代码就出事了。
             */
            String sharedHash = passwordEncoder.encode("password123");

            List<User> users = new ArrayList<>(userCount + 1);
            users.add(User.builder()
                    .username("admin")
                    .email("admin@orderstream.local")
                    .password(sharedHash)
                    .role(User.Role.ADMIN)
                    .build());

            for (int i = 1; i <= userCount; i++) {
                users.add(User.builder()
                        .username("user" + i)
                        .email("user" + i + "@orderstream.local")
                        .password(sharedHash)
                        .role(User.Role.USER)
                        .build());
            }

            // saveAll + hibernate.jdbc.batch_size=50 让它变成 ~20 条批量 INSERT，
            // 而不是 1001 次单条往返
            userRepository.saveAll(users);
            log.info("[种子数据] 已创建 1 个管理员 + {} 个测试用户（密码统一为 password123）", userCount);
        };
    }
}
