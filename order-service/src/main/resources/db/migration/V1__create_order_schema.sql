-- ============================================================================
-- order-service 的独立数据库 (order_db)
--
-- 这里【没有】users 表、【没有】menu_items 表，也没有指向它们的外键。
-- 订单里的 user_id 和 menu_item_id 只是数字，不是外键约束。
--
-- 这正是 database-per-service 最直观的体现，也是它最大的代价：
--   数据库【不再能】保证引用完整性。
--   理论上可以插入一个 user_id=99999 的订单，哪怕这个用户不存在。
--
-- 怎么办：
--   1) 写入路径上校验（下单时身份来自网关验过签的 JWT，user_id 必然有效）
--   2) 菜品在下单时通过 REST 调 menu-service 校验，校验完把名称和价格
--      【快照】进订单，之后再也不需要回查
--   3) 对账任务定期扫描孤儿数据
--
-- 换来的是：menu-service 可以独立扩容、独立发版、改自己的表结构而不影响订单，
-- 而且它挂掉时不会导致订单库不可用。这个取舍在服务规模变大后是值得的。
-- ============================================================================

CREATE TABLE orders (
    id            BIGSERIAL PRIMARY KEY,
    -- 只是个数字，不是 REFERENCES users(id)。见上面的说明
    user_id       BIGINT       NOT NULL,
    restaurant_id BIGINT       NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    -- 订单总额。冗余存储（可以由明细算出来）是【有意的】：
    --   1) 避免每次查询订单列表都要 JOIN 明细表做 SUM
    --   2) 更重要的是，它是一个【历史快照】。明细里的价格是下单时的价格，
    --      总额也必须锁定在下单那一刻。财务数据永远存事实，不存"可以算出来的东西"
    total_amount  NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    -- 取消原因。CANCELLED 状态下必须能告诉用户为什么被取消（库存不足？支付失败？）
    cancel_reason VARCHAR(500),
    -- JPA 乐观锁。防止"管理员改状态"和"Saga 推进状态"并发时互相覆盖(lost update)
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_orders_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'PREPARING', 'DELIVERED', 'CANCELLED')
    )
);

-- ============================================================================
-- 索引设计 —— 每一条都对应一个真实查询
-- ============================================================================

-- 1) 用户查自己的订单列表：
--        WHERE user_id = ? ORDER BY created_at DESC LIMIT 20
--
--    【这是复合索引，不是单列索引】。原版只建了 (user_id)，那只解决了一半问题：
--      单列索引能加速 WHERE 过滤，但 ORDER BY created_at DESC 仍然需要
--      把命中的所有行捞出来再排序 —— EXPLAIN 里会看到一个 Sort 节点，
--      数据量大时还会变成 "Sort Method: external merge Disk"（落磁盘排序），
--      延迟从毫秒级跳到秒级。
--
--    复合索引 (user_id, created_at DESC) 让 Postgres 沿着索引顺序直接读前 20 行，
--    Sort 节点完全消失。这就是"加了索引"和"加对了索引"的区别。
--
--    列顺序为什么是 user_id 在前：
--      复合索引只能利用"最左前缀"。(user_id, created_at) 能服务
--      「按 user_id 过滤」和「按 user_id 过滤再按 created_at 排序」；
--      反过来 (created_at, user_id) 对我们的查询几乎没用。
--      规则：等值过滤的列放前面，范围/排序的列放后面。
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

-- 2) 餐厅后台查待处理订单：
--        WHERE restaurant_id = ? AND status = 'PENDING' ORDER BY created_at
--
--    【原版在这里有两个问题】：
--      a) orders.restaurant_id 上【根本没有索引】—— 餐厅后台的核心查询全表扫描
--      b) 单独建了一个 (status) 索引，但 status 只有 5 个枚举值，是【低基数】列。
--         如果 PENDING 占了 20% 的行，Postgres 的查询规划器会直接【无视】这个索引
--         走顺序扫描 —— 因为随机读 20% 的行比顺序读全表还慢。
--         这个索引不仅没用，还在每次写入时白白增加维护开销。
--
--    正确做法是【部分索引】：只索引真正要查的那部分数据。
--    已完成/已取消的订单会累积到几千万条，但"待处理"永远只有几百条。
--    这个索引因此永远很小、常驻内存、查询是微秒级。
CREATE INDEX idx_orders_restaurant_pending
    ON orders (restaurant_id, created_at)
    WHERE status IN ('PENDING', 'CONFIRMED', 'PREPARING');

CREATE TABLE order_items (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    menu_item_id  BIGINT         NOT NULL,
    -- 【菜品名称的快照】—— 这是跨服务数据冗余的典型例子。
    -- 不存的话，查历史订单就必须调用 menu-service 拿名称：
    --   1) 多一次跨服务调用，订单列表变成 N+1 次 RPC
    --   2) menu-service 挂了，订单详情就打不开
    --   3) 菜品被删除后，历史订单永远显示不出名字
    -- 冗余存储让订单成为一个【自包含的历史事实】，这在交易系统里是标准做法
    menu_item_name VARCHAR(200)  NOT NULL,
    quantity      INTEGER        NOT NULL CHECK (quantity > 0),
    -- 下单那一刻的单价快照。餐厅事后涨价绝不能影响已成交的订单
    unit_price    NUMERIC(10, 2) NOT NULL CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

-- ============================================================================
-- Saga 状态机 —— 分布式事务的"日志"
-- ============================================================================
CREATE TABLE order_sagas (
    saga_id       VARCHAR(36)  PRIMARY KEY,
    order_id      BIGINT       NOT NULL UNIQUE REFERENCES orders(id),
    state         VARCHAR(30)  NOT NULL,
    total_amount  NUMERIC(12, 2) NOT NULL,
    -- 支付成功后记下支付单号，退款补偿时要用它定位原交易
    payment_id    BIGINT,
    failure_reason VARCHAR(500),
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_saga_state CHECK (
        state IN ('STOCK_PENDING', 'PAYMENT_PENDING', 'COMPLETED', 'COMPENSATED', 'FAILED')
    )
);

-- 卡死 Saga 的兜底扫描：状态还在进行中、但很久没更新了。
-- 部分索引保证它只包含"进行中"的 Saga（通常几十条），扫描极快
CREATE INDEX idx_sagas_in_flight
    ON order_sagas (updated_at)
    WHERE state IN ('STOCK_PENDING', 'PAYMENT_PENDING');

-- ============================================================================
-- 幂等键 —— 防止重复下单
-- ============================================================================
--
-- 【为什么限流不能替代它】
--   限流防的是"恶意的大量请求"，幂等防的是"善意的重复请求"：
--     - 用户手抖点了两次提交
--     - 客户端请求超时后自动重试（但服务端其实已经成功了）
--     - 移动网络下的重复投递
--   这些都是完全正常的一次请求，限流不该拦、也拦不住，但绝不能产生两个订单。
--
-- 【机制】
--   客户端在 HTTP 头里带一个自己生成的 Idempotency-Key（通常是 UUID）。
--   服务端第一次见到它就正常处理并记录 key -> order_id 的映射；
--   再次见到同一个 key，直接返回第一次的结果，不重新下单。
--   这是 Stripe/Square 等支付网关对外暴露的标准机制。
CREATE TABLE order_idempotency_keys (
    idempotency_key VARCHAR(64) NOT NULL,
    -- 【必须把 user_id 纳入主键】
    --   只用 key 做主键的话，攻击者可以猜/撞别人的 key，
    --   然后拿到【别人订单的 ID】—— 这本身就是一个信息泄露漏洞。
    --   加上 user_id 后，每个用户的 key 空间是隔离的。
    user_id         BIGINT      NOT NULL,
    order_id        BIGINT      NOT NULL REFERENCES orders(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (idempotency_key, user_id)
);

CREATE INDEX idx_idempotency_created ON order_idempotency_keys (created_at);

-- ============================================================================
-- Outbox + 幂等去重（来自 messaging 共享模块）
-- 每个服务一份，因为它们必须和本服务的业务表在同一个数据库里，
-- 才能被同一个本地事务覆盖
-- ============================================================================
CREATE TABLE outbox_events (
    id            BIGSERIAL PRIMARY KEY,
    event_id      VARCHAR(36)  NOT NULL UNIQUE,
    topic         VARCHAR(100) NOT NULL,
    message_key   VARCHAR(100),
    payload       TEXT         NOT NULL,
    event_type    VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMPTZ,
    attempt_count INTEGER      NOT NULL DEFAULT 0,
    last_error    VARCHAR(500)
);

CREATE INDEX idx_outbox_unsent  ON outbox_events (id)      WHERE sent_at IS NULL;
CREATE INDEX idx_outbox_sent_at ON outbox_events (sent_at) WHERE sent_at IS NOT NULL;

CREATE TABLE processed_events (
    event_id     VARCHAR(36)  NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_time ON processed_events (processed_at);
