package com.orderstream.common.security;

/**
 * 网关认证后注入给下游服务的身份头。
 *
 * 设计：【边缘认证 + 内网信任传递】
 *   API Gateway 在边缘解析并验证一次 JWT，然后把身份信息以 HTTP header 的形式传给下游。
 *   下游服务不再解析 JWT。
 *
 * 为什么不让每个服务自己验 JWT：
 *   那意味着签名密钥要分发到 7 个服务里，密钥轮换变成一场灾难；
 *   而且每个服务都要维护一套 JWT 解析逻辑，版本漂移只是时间问题。
 *
 * 这个设计的代价（必须清楚）：
 *   内网变成了信任边界。如果攻击者能直接访问 order-service:8084，他只要自己加一个
 *   "X-User-Role: ADMIN" 头就拿到管理员权限 —— 完全绕过网关。
 *
 * 两道防线：
 *   1. 网关必须【剥离】所有入站请求里的这些头（见 IdentityHeaderStripFilter），
 *      否则客户端可以直接伪造。这是本设计里最容易被漏掉、也最致命的一步。
 *   2. 网络层面：下游服务只监听内网，不对外暴露端口（docker-compose 里不做 ports 映射）。
 *      生产环境用 service mesh 的 mTLS 做服务间双向认证才是完整解。
 */
public final class AuthHeaders {

    private AuthHeaders() {}

    public static final String USER_ID  = "X-User-Id";
    public static final String USERNAME = "X-Username";
    public static final String ROLE     = "X-User-Role";

    /** 全链路追踪 ID：网关生成，贯穿所有服务和 Kafka 事件，排障时靠它串起一次请求 */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** 网关必须剥离的、绝不允许客户端自带的头 */
    public static final String[] CLIENT_SPOOFABLE = { USER_ID, USERNAME, ROLE };
}
