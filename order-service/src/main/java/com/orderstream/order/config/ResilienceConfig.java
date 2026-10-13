package com.orderstream.order.config;

import com.orderstream.common.web.BusinessException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 熔断器配置。每一项参数都有明确的理由，没有一个是默认值抄来的。
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()

                /*
                 * 【滑动窗口：按"最近 N 次调用"统计，而不是"最近 N 秒"】
                 *
                 * TIME_BASED（时间窗口）在低流量时会失真：
                 *   10 秒内只来了 2 个请求、都失败了 -> 失败率 100% -> 熔断。
                 *   但 2 个样本根本说明不了下游有问题
                 *   （可能只是两个格式错误的请求）。
                 *
                 * COUNT_BASED 保证了统计的样本量：必须攒够 20 次调用才判断。
                 * 对流量不均匀的服务，这个选择更稳健。
                 */
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)

                /*
                 * 最小调用次数：不足 10 次绝不熔断。
                 *
                 * 没有这一项的话：服务刚启动，第一个请求失败 = 失败率 100%
                 * = 立刻熔断 = 所有后续请求被拒。冷启动直接自杀。
                 * 这是熔断器配置里最容易忘、后果最尴尬的一项。
                 */
                .minimumNumberOfCalls(10)

                /*
                 * 失败率阈值 50%。
                 *
                 * 为什么不更低（比如 20%）：
                 *   下游偶发抖动（GC 停顿、一次网络重传）是正常的。
                 *   阈值太低会频繁误熔断，把"偶尔慢"升级成"完全不可用"，
                 *   反而降低了整体可用性。
                 *
                 * 为什么不更高（比如 90%）：
                 *   那意味着要等下游几乎全挂才熔断，
                 *   在此之前大量请求已经在白白等待超时了。
                 *
                 * 50% 的含义是：一半以上的调用都失败了，下游确实病了。
                 */
                .failureRateThreshold(50f)

                /*
                 * 【慢调用也算失败 —— 这一项经常被忽略，但极其重要】
                 *
                 * 只统计"抛异常"会漏掉最危险的一种故障：
                 *   下游没挂，但每个请求都要 1.9 秒（刚好不超时）。
                 *   技术上全是成功，失败率 0%，熔断器永远不开。
                 *   但调用方的线程和连接全被这些慢请求占满，实际上已经瘫痪。
                 *
                 * 把"超过 1 秒"定义为慢调用，慢调用率超过 50% 同样触发熔断。
                 * 这样"持续变慢"和"直接报错"会被同等对待 ——
                 * 从调用方的视角看，它们造成的伤害本来就是一样的。
                 */
                .slowCallDurationThreshold(Duration.ofSeconds(1))
                .slowCallRateThreshold(50f)

                /*
                 * 打开后等 10 秒再进入半开状态试探。
                 *
                 * 太短：下游还没恢复就被再次打满，熔断器反复开合（flapping）
                 * 太长：下游早就好了，我们还在拒绝请求，白白损失可用性
                 * 10 秒大致对应一次服务重启或一次 GC 风暴的恢复时间。
                 */
                .waitDurationInOpenState(Duration.ofSeconds(10))

                /*
                 * 半开状态放 5 个请求过去试探。
                 *
                 * 不用 1 个：单个请求成功可能只是运气。
                 * 不用 50 个：如果下游还没好，这 50 个又是一波打击。
                 * 少量探针是"试探"和"不伤害"之间的平衡。
                 */
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)

                /*
                 * 【哪些异常不算故障 —— 这个配置决定了熔断器是否有意义】
                 *
                 * BusinessException 代表业务结果，不是技术故障：
                 *   餐厅不存在、菜品售罄 —— 下游完全健康，
                 *   它正确地告诉了我们一个业务事实。
                 *
                 * 如果把这些算进失败率，后果是：
                 *   爬虫扫不存在的餐厅 ID
                 *   -> 失败率飙到 100% -> 熔断器打开
                 *   -> 正常用户的正常请求也被拒绝
                 *
                 * 用户的错误输入把系统变成了自我拒绝服务。
                 * 这是熔断器最常见的配置错误，而且只在特定流量模式下才暴露。
                 *
                 * 判断标准很简单：这个异常说明【下游有问题】吗？
                 *   是  -> 计入失败（超时、连接拒绝、500）
                 *   否  -> 忽略（400、404、业务规则拒绝）
                 */
                .ignoreExceptions(BusinessException.class)

                .build();

        return CircuitBreakerRegistry.of(config);
    }
}
