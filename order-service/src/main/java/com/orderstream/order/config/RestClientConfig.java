package com.orderstream.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RestClientConfig {

    /**
     * 调用 menu-service 的 HTTP 客户端。
     *
     * 用 RestClient（Spring 6.1 引入）而不是 RestTemplate：
     *   RestTemplate 已经进入维护模式，官方推荐新代码用 RestClient。
     *   RestClient 是同步的、流式 API 的，和 WebClient 共享底层基础设施，
     *   但不需要引入整个响应式栈。
     *
     * ========================================================================
     * 【超时是同步调用里最重要的配置，没有之一】
     * ========================================================================
     *
     *   Java 的 HTTP 客户端【默认没有超时】—— 这是个非常危险的默认值。
     *   下游服务如果不响应（进程假死、网络黑洞、防火墙丢包），
     *   调用方的线程会【永远】挂在那里。
     *
     *   后果是级联失败(cascading failure)：
     *     menu-service 假死
     *     -> order-service 的线程一个接一个挂死在等待上
     *     -> order-service 线程耗尽，所有接口（包括不依赖 menu 的）全部无响应
     *     -> 上游网关的线程也跟着挂死
     *     -> 整个系统被一个下游服务拖垮
     *
     *   一个下游的局部故障，通过"无限等待"扩散成了全站故障。
     *   这是微服务架构最典型的雪崩模式。
     *
     *   【超时时间怎么定】
     *     不是拍脑袋。应该取下游的 p99 延迟再留点余量。
     *     menu-service 的批量查询走主键索引，p99 应该在 50ms 以内，
     *     所以 2 秒是极充裕的 —— 超过 2 秒必然是出问题了，等下去没有意义。
     *
     *     设太长：故障时线程堆积，失去快速失败的价值
     *     设太短：正常的抖动被误判为失败，触发不必要的熔断
     */
    @Bean
    public RestClient menuServiceRestClient(MenuServiceProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        // 建立 TCP 连接的超时。下游正常时这一步是毫秒级，
        // 1 秒还连不上说明对方进程没起来或网络不通
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        // 建连之后等待响应的超时。这是防线程挂死的关键
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(properties.getUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 并行调用用的执行器。
     *
     * ========================================================================
     * 【Java 21 虚拟线程改变了这里的设计思路】
     * ========================================================================
     *
     * 传统做法是开一个【固定大小的平台线程池】，比如 20 个线程。
     * 这么做的目的叫【舱壁隔离(bulkhead)】：
     *   给"调用 menu-service"这件事划定一个资源上限，
     *   menu-service 挂了最多堵死这 20 个线程，
     *   主请求线程池（Tomcat 的 200 个）不受影响，其他接口照常工作。
     *   就像船的隔水舱，一个舱进水不会沉船。
     *
     * 用虚拟线程后，情况变了：
     *   虚拟线程极其廉价（几 KB 内存，JVM 调度），阻塞时会自动从载体线程上卸载。
     *   "线程耗尽"这个故障模式基本消失了 —— 开几万个也没事。
     *
     * 【但这不等于不需要隔离了】—— 这是个容易想错的地方。
     *   虚拟线程解决的是"线程"这个资源，没解决【下游的容量】和【内存】：
     *     - menu-service 只能扛 1000 QPS，你用虚拟线程无限发请求只会把它打死
     *     - 每个在途请求都持有缓冲区和对象，几万个在途请求照样 OOM
     *
     *   所以隔离机制要从"线程池大小"换成【信号量(Semaphore)】：
     *   限制的是"同时在途的请求数"，而不是"线程数"。
     *   Resilience4j 的 SemaphoreBulkhead 正是干这个的。
     *
     * 一句话总结这个演进：
     *   虚拟线程让"线程"不再是稀缺资源，于是限流的对象要回归到【真正稀缺的东西】
     *   —— 下游容量、数据库连接、内存。
     */
    @Bean(destroyMethod = "close")
    public ExecutorService downstreamCallExecutor() {
        // 每个任务一个虚拟线程。没有池、没有队列、没有大小限制 ——
        // 因为虚拟线程本身就不需要池化（池化是为了复用昂贵的资源，
        // 而虚拟线程的创建成本低到不值得池化）
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConfigurationProperties(prefix = "orderstream.menu-service")
    public MenuServiceProperties menuServiceProperties() {
        return new MenuServiceProperties();
    }

    /**
     * 用 @ConfigurationProperties 而不是散落的 @Value：
     *   - 配置项集中，一眼看得出这个客户端有哪些可调参数
     *   - 支持 Duration 等类型的自动转换（"2s" -> Duration.ofSeconds(2)）
     *   - 可以配合 @Validated 做启动时校验，配置写错在启动时就失败
     */
    @lombok.Data
    public static class MenuServiceProperties {
        private String url = "http://localhost:8082";
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(2);
        /** 并行调用的整体超时，必须大于单个调用的 readTimeout */
        private Duration overallTimeout = Duration.ofSeconds(3);
    }
}
