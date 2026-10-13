package com.orderstream.order.client;

import com.orderstream.common.security.AuthHeaders;
import com.orderstream.common.security.CurrentUser;
import com.orderstream.common.web.BusinessException;
import com.orderstream.order.config.RestClientConfig.MenuServiceProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 调用 menu-service 的客户端。
 *
 * 这是整个系统里【唯一】的同步跨服务调用。其他所有跨服务交互都走 Kafka。
 *
 * 【为什么这一处必须同步】
 *   下单时必须【立刻】知道菜品的价格和有效性，才能算出订单金额返回给用户。
 *   做成异步的话，用户点了下单，接口立刻返回"订单创建中"，
 *   几秒后才通过推送告诉他实际金额 —— 这个体验是不可接受的。
 *
 *   所以这里的取舍很明确：
 *     用【同步调用带来的耦合和故障传播风险】，换取【用户能立刻看到订单金额】。
 *   代价必须用超时 + 熔断来控制，这正是下面所有代码在做的事。
 *
 * 【判断该同步还是异步的通用标准】
 *   问一句："调用方能不能在不知道结果的情况下，给用户一个可接受的答复？"
 *     能  -> 异步（发通知、更新分析数据、扣积分）
 *     不能 -> 同步（校验价格、检查权限、任何会影响返回值的事）
 */
@Slf4j
@Component
public class MenuServiceClient {

    private static final String CIRCUIT_BREAKER_NAME = "menu-service";

    private final RestClient restClient;
    private final ExecutorService executor;
    private final CircuitBreaker circuitBreaker;
    private final MenuServiceProperties properties;

    public MenuServiceClient(RestClient menuServiceRestClient,
                             ExecutorService downstreamCallExecutor,
                             CircuitBreakerRegistry circuitBreakerRegistry,
                             MenuServiceProperties menuServiceProperties) {
        this.restClient = menuServiceRestClient;
        this.executor = downstreamCallExecutor;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.properties = menuServiceProperties;

        // 熔断器状态变化是重要的运维事件，必须记日志。
        // 生产环境还应该把它变成一个 Prometheus 指标并配告警：
        // 熔断器打开 = 某个下游正在故障，值班的人需要立刻知道
        this.circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("[熔断器] menu-service 状态变化: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    /**
     * ========================================================================
     * 【CompletableFuture 并行调用 —— 并行化外部调用】
     * ========================================================================
     *
     * 下单需要两份数据，它们【互不依赖】：
     *   1) 餐厅是否存在且在营业
     *   2) 这些菜品的当前价格和名称
     *
     * 串行执行：50ms + 50ms = 100ms
     * 并行执行：max(50ms, 50ms) = 50ms
     *
     * 延迟直接减半。在下单这个用户能直接感知的路径上，这是实打实的体验提升。
     *
     * 【关键陷阱：SecurityContextHolder 是 ThreadLocal】
     *   身份信息存在当前线程的 ThreadLocal 里。一旦把工作丢进线程池，
     *   新线程里的 SecurityContextHolder 是【空的】——
     *   于是调用 menu-service 时带不上身份头，对方返回 401，
     *   而错误信息会非常令人困惑（"我明明登录了啊"）。
     *
     *   解法就是下面这两行：在【进入异步之前】把身份取出来，
     *   作为普通变量捕获进 lambda。
     *   这个坑在所有用 ThreadLocal 传递上下文的场景都存在
     *   （MDC 里的 correlationId 也一样会丢）。
     */
    public MenuSnapshot fetchForOrder(Long restaurantId, Collection<Long> menuItemIds) {

        // 必须在异步之前取出来！进了线程池就拿不到了
        CurrentUser user = CurrentUser.get();

        CompletableFuture<RestaurantView> restaurantFuture =
                CompletableFuture.supplyAsync(
                        () -> withCircuitBreaker(() -> getRestaurant(restaurantId, user)), executor);

        CompletableFuture<List<MenuItemView>> itemsFuture =
                CompletableFuture.supplyAsync(
                        () -> withCircuitBreaker(() -> getMenuItems(menuItemIds, user)), executor);

        try {
            /*
             * allOf + join 等待两个都完成。
             *
             * 【为什么用 allOf(...).get(timeout) 而不是分别 join()】
             *   分别 join 的话，第一个 join 会等满它自己的超时，
             *   第二个再等满它的 —— 最坏情况是两个超时相加。
             *   allOf 给的是【整体】的超时上限，语义更准确，
             *   也更贴近"用户最多愿意等 3 秒"这个真实约束。
             *
             * 【超时时间的层次关系必须理清楚】
             *   单次 HTTP readTimeout (2s) < 整体并行超时 (3s) < 网关的 response-timeout (10s)
             *   由内到外逐层放大。如果搞反了（外层比内层还短），
             *   外层会先超时断开，而内层还在傻等，产生"幽灵请求"：
             *   用户看到失败，但下游其实成功执行了。这在下单场景下就是重复订单的来源。
             */
            CompletableFuture.allOf(restaurantFuture, itemsFuture)
                    .get(properties.getOverallTimeout().toMillis(), TimeUnit.MILLISECONDS);

            return new MenuSnapshot(restaurantFuture.join(), itemsFuture.join());

        } catch (TimeoutException e) {
            // 主动取消还没完成的任务，释放它们占用的连接。
            // 不取消的话，这些请求会继续跑完，白白消耗下游容量 ——
            // 而下游本来就已经慢了，这等于雪上加霜
            restaurantFuture.cancel(true);
            itemsFuture.cancel(true);
            throw new BusinessException("菜单服务响应超时，请稍后重试");

        } catch (ExecutionException e) {
            // ExecutionException 只是个包装，真正的异常在 cause 里。
            // 直接把它抛出去的话，上层看到的错误信息是
            // "java.util.concurrent.ExecutionException"，毫无用处
            Throwable cause = e.getCause();
            if (cause instanceof BusinessException be) {
                throw be;
            }
            log.error("[菜单服务] 调用失败", cause);
            throw new BusinessException("菜单服务暂时不可用，请稍后重试");

        } catch (InterruptedException e) {
            /*
             * 【捕获 InterruptedException 后必须恢复中断标志】
             *
             * catch 住这个异常时，JVM 已经把线程的中断标志【清除】了。
             * 如果不调 Thread.currentThread().interrupt() 恢复它，
             * 调用栈上层就永远不知道"有人请求过中断这个线程"，
             * 优雅停机时这个线程可能拒绝退出。
             *
             * 这是 Java 并发里最经典的一条规约：
             * 要么向上抛 InterruptedException，要么恢复中断标志，二选一，不能都不做。
             */
            Thread.currentThread().interrupt();
            throw new BusinessException("请求被中断");
        }
    }

    // ------------------------------------------------------------------
    // 实际的 HTTP 调用
    // ------------------------------------------------------------------

    private RestaurantView getRestaurant(Long restaurantId, CurrentUser user) {
        try {
            return restClient.get()
                    .uri("/api/restaurants/{id}", restaurantId)
                    .headers(h -> injectIdentity(h, user))
                    .retrieve()
                    .body(RestaurantView.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // 404 是【业务结果】不是技术故障 —— 翻译成业务异常，
            // 这样它不会被熔断器计入失败率（见下面 recordExceptions 的配置）。
            // 如果把 404 也算进熔断统计，那么大量用户查询不存在的餐厅
            // 就会把熔断器打开，导致正常请求也被拒绝 —— 用户的错误输入
            // 变成了系统的自我拒绝服务
            throw new BusinessException("餐厅不存在: " + restaurantId);
        }
    }

    private List<MenuItemView> getMenuItems(Collection<Long> ids, CurrentUser user) {
        String idsParam = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<MenuItemView> items = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/menu-items").queryParam("ids", idsParam).build())
                .headers(h -> injectIdentity(h, user))
                .retrieve()
                // ParameterizedTypeReference 是为了保留泛型信息。
                // Java 的类型擦除让 List<MenuItemView>.class 写不出来，
                // 这个匿名子类的技巧让运行时能拿到完整的泛型类型
                .body(new ParameterizedTypeReference<List<MenuItemView>>() {});
        return items == null ? List.of() : items;
    }

    /**
     * 把当前用户的身份透传给下游。
     *
     * 【这是"服务间调用保留调用者身份"的做法，不是"服务用自己的身份"】
     *   两种模型的区别很重要：
     *     - 透传用户身份：menu-service 知道"是用户 42 在通过 order-service 查菜品"，
     *       可以做针对该用户的授权和审计。
     *     - 服务身份(service account)：menu-service 只知道"order-service 在调我"，
     *       失去了用户维度的审计能力。
     *
     *   订单场景下透传更合适，因为下游可能需要做用户级的判断。
     *   代价是：order-service 被攻破后，攻击者可以伪造任意用户身份调 menu-service。
     *   完整的方案是 token exchange（用当前 token 换一个受限的下游 token），
     *   但那需要完整的 OAuth2 基础设施。
     *
     * 注意 correlationId 也要透传，否则调用链在这里就断了。
     */
    private void injectIdentity(org.springframework.http.HttpHeaders headers, CurrentUser user) {
        headers.set(AuthHeaders.USER_ID, String.valueOf(user.getUserId()));
        headers.set(AuthHeaders.USERNAME, user.getUsername());
        headers.set(AuthHeaders.ROLE, user.getRole());

        // MDC 里的 correlationId 是网关经日志上下文传进来的。
        // 注意：MDC 同样是 ThreadLocal，在虚拟线程里读它得靠继承(InheritableThreadLocal)，
        // 这里做了 null 保护
        String correlationId = org.slf4j.MDC.get("correlationId");
        if (correlationId != null) {
            headers.set(AuthHeaders.CORRELATION_ID, correlationId);
        }
    }

    /**
     * 用熔断器包裹调用。
     *
     * ========================================================================
     * 【熔断器解决的问题：不要对一个已经倒下的服务继续施压】
     * ========================================================================
     *
     *   menu-service 挂了之后，如果 order-service 继续每秒发 1000 个请求过去：
     *     1) 每个请求都要等满 2 秒超时才失败 —— 用户白等 2 秒得到一个错误
     *     2) 这 1000 QPS 持续打在正在重启/恢复的 menu-service 上，让它永远起不来
     *
     *   熔断器的三个状态：
     *     CLOSED（关闭，正常）：请求正常通过，同时统计失败率
     *     OPEN（打开，熔断）：失败率超阈值后进入。【所有请求立刻失败，不再发出去】
     *                        用户 0 毫秒得到错误，下游得到喘息空间
     *     HALF_OPEN（半开，试探）：等待一段时间后放几个请求过去探路。
     *                        成功就回到 CLOSED，失败就回到 OPEN
     *
     *   核心价值有两个，缺一不可：
     *     a) 对调用方：快速失败，不浪费线程和用户的时间
     *     b) 对被调方：【减压】，给它恢复的机会
     *   很多人只记得 a，但 b 其实更关键 —— 没有 b，故障就永远无法自愈。
     */
    private <T> T withCircuitBreaker(Supplier<T> call) {
        return circuitBreaker.executeSupplier(call);
    }

    /** 并行调用的结果聚合 */
    public record MenuSnapshot(RestaurantView restaurant, List<MenuItemView> items) {

        /** 转成 Map 方便按 ID 查找，避免下单时在列表里做 O(n) 线性搜索 */
        public Map<Long, MenuItemView> itemsById() {
            return items.stream().collect(Collectors.toMap(MenuItemView::id, i -> i));
        }
    }
}
