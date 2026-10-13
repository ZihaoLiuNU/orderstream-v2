package com.orderstream.menu.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Slf4j
@EnableCaching
@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        /*
         * ====================================================================
         * 【默认类型信息(default typing) —— 这里踩过一个坑，而修法本身有安全含义】
         * ====================================================================
         *
         * 【坑是什么】
         *   我第一版只写了 registerModule + setVisibility，没开 default typing。
         *   结果是一个很迷惑的 bug：
         *     第一次请求（缓存未命中）正常返回；
         *     第二次请求（缓存命中）直接 500。
         *
         *   原因：JSON 本身不带类型信息。写进 Redis 的是
         *     {"id":1,"name":"测试餐厅"}
         *   读出来时 Jackson 不知道该还原成 RestaurantResponse，
         *   于是给了一个 LinkedHashMap，然后在赋值时 ClassCastException。
         *
         *   注意 GenericJackson2JsonRedisSerializer 的【无参构造】会自己开启
         *   default typing，但【传入自定义 ObjectMapper 的构造】不会 ——
         *   它完全尊重你给的 mapper。这个差异在文档里不显眼，
         *   是"自定义了一半"导致的典型问题。
         *
         * 【为什么修法有安全含义】
         *   开启 default typing 后，JSON 里会多出一个类名字段：
         *     ["com.orderstream.menu.dto.RestaurantResponse", {"id":1,...}]
         *   反序列化时 Jackson 会【按这个字符串去加载并实例化类】。
         *
         *   如果攻击者能往 Redis 里写入任意内容（Redis 未授权访问是极常见的
         *   配置失误，历史上造成过大量入侵），他就可以指定一个
         *   "gadget 类"——某个在构造或 setter 里会执行危险操作的类
         *   （典型的如 JNDI 相关类），从而达成【远程代码执行】。
         *   这就是 Jackson 历史上一长串 CVE 的成因。
         *
         *   所以【绝不能】用 enableDefaultTyping() 这种无限制的老 API，
         *   也不该用 LaissezFaireSubTypeValidator（顾名思义：放任不管）。
         *   正确做法是下面这样：用 BasicPolymorphicTypeValidator 建立白名单，
         *   只允许我们自己的包和少量必要的 JDK 集合类。
         *   即使 Redis 被写入恶意数据，反序列化也会在类型校验阶段直接失败。
         *
         *   一句话：【永远不要让"要实例化哪个类"由不可信输入决定】。
         */
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.orderstream.")   // 只允许本项目的类
                .allowIfSubType("java.util.")         // List/Map 等容器
                .allowIfSubType("java.time.")         // Instant 等
                .allowIfSubType("java.math.")         // BigDecimal
                .build();

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())   // 支持 Instant/LocalDateTime
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        mapper.activateDefaultTyping(
                typeValidator,
                ObjectMapper.DefaultTyping.NON_FINAL,   // 只给非 final 类写类型信息，减小体积
                JsonTypeInfo.As.PROPERTY);

        return RedisCacheConfiguration.defaultCacheConfig()
                /*
                 * 【TTL 是缓存一致性的最后一道保险】
                 *
                 * 即使 @CacheEvict 因为某种原因没执行（代码分支漏了、Redis 当时不可用、
                 * 极端竞态导致旧值被写回），TTL 保证最多 10 分钟后数据自动刷新。
                 *
                 * 【永不过期的缓存是最危险的】：一旦出现不一致就是永久的，
                 * 而且通常要等用户投诉才会发现。
                 * 任何缓存都必须设 TTL，哪怕设得很长。
                 */
                .entryTtl(Duration.ofMinutes(10))

                /*
                 * 不缓存 null 值。
                 *
                 * 这是个取舍：
                 *   缓存 null 能防【缓存穿透】—— 攻击者用不存在的 ID 疯狂请求，
                 *   每次都未命中、每次都打到数据库。
                 *   但缓存 null 会占用内存，而且"资源刚创建就查不到"的体验很差。
                 *
                 * 我选择不缓存 null，因为：
                 *   餐厅 ID 是自增的、范围可预测，穿透攻击的成本对攻击者也不低；
                 *   而且网关已经有限流，能挡住高频的无效请求。
                 * 如果 ID 是 UUID 或用户可控的字符串，那就必须缓存 null 或上布隆过滤器。
                 */
                .disableCachingNullValues()

                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                /*
                 * value 用 JSON 序列化，不用 JDK 原生序列化。三个理由：
                 *   1) JDK 序列化的产物是二进制，用 redis-cli 看是乱码，完全无法排障
                 *   2) JDK 序列化对类结构变化极度敏感，加个字段就 InvalidClassException，
                 *      灰度发布期间新旧版本共用缓存必然出事
                 *   3) JDK 反序列化是著名的 RCE 攻击面（readObject gadget chain）
                 */
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper)))

                /*
                 * ============================================================
                 * 【key 前缀里带版本号 —— 这是踩过坑之后加的，非常实用】
                 * ============================================================
                 *
                 * 【踩到的坑】
                 *   我改了序列化配置（加上 default typing）之后重新部署，
                 *   结果所有缓存命中的请求全部 500：
                 *     InvalidTypeIdException: missing type id property '@class'
                 *
                 *   原因：Redis 里还存着【改格式之前】写入的旧数据。
                 *   新代码按新格式去读旧数据，必然失败。
                 *   而且它不是"读失败就回源"那么简单 ——
                 *   反序列化异常发生在 Spring Cache 内部，
                 *   虽然 CacheErrorHandler 能兜住，但每次读都要先失败一次，
                 *   而且如果 handler 配错了就是直接 500。
                 *
                 * 【为什么这在生产环境更危险】
                 *   滚动发布期间，新旧版本的实例【同时】在跑，共用同一个 Redis：
                 *     旧实例写入旧格式 -> 新实例读到就报错
                 *     新实例写入新格式 -> 旧实例读到也报错
                 *   两边互相污染，故障会持续整个发布窗口。
                 *   而且回滚也救不了，因为缓存里已经混入了新格式数据。
                 *
                 * 【解法：把序列化格式版本编进 key】
                 *   格式一变就把 v1 改成 v2，新老版本各读各的 key 空间，
                 *   完全不会互相干扰。旧 key 靠 TTL 自然过期，不需要手工清理。
                 *
                 *   这是"通过命名空间隔离来避免不兼容变更"的通用套路，
                 *   和数据库的 expand-contract 迁移、API 的 /v1 /v2 是同一个思路：
                 *   【不要原地修改，而是并行新建再切换】。
                 */
                .computePrefixWith(cacheName -> "menu:v2:" + cacheName + ":");
    }

    /**
     * ========================================================================
     * 【缓存故障降级 —— 这个 Bean 决定了 Redis 挂掉时系统是变慢还是崩溃】
     * ========================================================================
     *
     * Spring Cache 的默认 CacheErrorHandler 是 SimpleCacheErrorHandler，
     * 它把所有缓存异常【原样抛出】。后果是：
     *   Redis 连接超时 -> @Cacheable 抛异常 -> 请求返回 500
     *   -> 本来只是"没有缓存加速"，变成了"整个查询接口不可用"。
     *
     * 这是个非常常见的生产事故模式：
     *   引入缓存是为了提升可用性和性能，结果反而引入了一个新的单点故障。
     *
     * 正确做法是把缓存异常降级成日志 + 直接走数据库：
     *   系统会变慢（每次都回源），但【依然可用】。
     *
     * 这体现了一个通用原则：
     *   区分【关键路径依赖】和【优化性依赖】。
     *   Postgres 是关键路径，它挂了服务就该挂（也没法工作）。
     *   Redis 是优化，它挂了必须能降级。
     *   架构里的每个外部依赖都要明确属于哪一类，并按此设计故障行为。
     */
    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                // 降级：当作缓存未命中，继续走数据库
                log.warn("[缓存降级] 读取失败，回源数据库 cache={} key={} error={}",
                        cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                // 写不进缓存不影响业务结果，数据已经从数据库正确返回了
                log.warn("[缓存降级] 写入失败 cache={} key={} error={}",
                        cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                /*
                 * 【这个分支比另外两个危险得多，必须区别对待】
                 *
                 * 删除缓存失败意味着：数据库已经更新了，但缓存里还是旧值。
                 * 吞掉异常等于接受了一段时间的数据不一致。
                 *
                 * 我仍然选择吞掉（而不是让写操作失败回滚），因为：
                 *   - 数据库的写入是正确的，回滚它反而丢失了用户的真实操作
                 *   - TTL(10分钟) 会兜底，不一致是有界的
                 * 但日志级别提到 error，让它能被告警捕获并人工确认。
                 *
                 * 如果业务不能容忍 10 分钟不一致，正确做法是：
                 * 把失效操作写进一个重试队列（本质上又是一个 outbox），
                 * 而不是简单地吞掉或简单地失败。
                 */
                log.error("[缓存降级] 失效失败，缓存中可能残留旧数据，将由 TTL 兜底 cache={} key={} error={}",
                        cache.getName(), key, ex.getMessage());
            }
        };
    }
}
