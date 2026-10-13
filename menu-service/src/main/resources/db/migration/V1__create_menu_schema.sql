-- ============================================================================
-- menu-service 的独立数据库 (menu_db)
--
-- 注意这里【没有】users 表、【没有】orders 表，也没有指向它们的外键。
-- 这是 database-per-service 的直接后果：menu-service 只知道菜品和库存，
-- 它根本不知道"用户"和"订单"是什么概念。
-- 跨服务的数据关联靠事件里冗余字段来解决，不靠外键。
-- ============================================================================

CREATE TABLE restaurants (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    address     VARCHAR(500) NOT NULL,
    description TEXT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 只索引在营业的餐厅。
-- 部分索引(partial index)的价值：下架的餐厅会越积越多，但列表查询永远只要在营的。
-- 全列索引会把所有下架餐厅也索引进去，白白增大索引体积、降低缓存命中率
CREATE INDEX idx_restaurants_active ON restaurants (id) WHERE active = TRUE;

CREATE TABLE menu_items (
    id             BIGSERIAL PRIMARY KEY,
    restaurant_id  BIGINT         NOT NULL REFERENCES restaurants(id),
    name           VARCHAR(200)   NOT NULL,
    description    TEXT,
    -- 钱【绝对不能】用 float/double。
    -- 浮点数是二进制的，0.1 在二进制里是无限循环小数，存不下。
    -- 经典后果：0.1 + 0.2 = 0.30000000000000004，对账永远差几分钱。
    -- NUMERIC(10,2) 是精确十进制，Java 侧对应 BigDecimal（不是 double！）
    price          NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    stock_quantity INTEGER        NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    available      BOOLEAN        NOT NULL DEFAULT TRUE,
    -- JPA 乐观锁版本号。见 MenuItem 实体里的说明
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 索引设计
-- ============================================================================

-- 1) 外键索引。
--    【Postgres 不会给外键自动建索引】—— 它只给 PRIMARY KEY 和 UNIQUE 自动建。
--    MySQL InnoDB 会自动建，所以很多人有肌肉记忆以为 FK 自带索引。
--    没有它，findByRestaurantId 就是全表顺序扫描。
--    另外它还能让「删除餐厅」变快：每次删父行，Postgres 都要检查子表有没有引用，
--    没索引就是一次全表扫描。
--
-- 2) 这是【复合 + 部分】索引，不是单列索引：
--    实际查询是 WHERE restaurant_id = ? AND available = TRUE ORDER BY name
--    - restaurant_id 放第一列：它是等值过滤，选择性最高
--    - name 放第二列：让 ORDER BY 直接走索引顺序，消除执行计划里的 Sort 节点
--    - WHERE available 做成部分索引：下架菜品不进索引
--    这一条索引同时干掉了「全表扫描」和「排序」两件事。
CREATE INDEX idx_menu_items_restaurant_name
    ON menu_items (restaurant_id, name)
    WHERE available = TRUE;

-- 3) order-service 会用 WHERE id IN (...) 批量查菜品 —— 走主键索引，无需额外建。

-- ============================================================================
-- 库存预留（Saga 步骤 1）
-- ============================================================================
CREATE TABLE stock_reservations (
    -- saga_id 直接做主键：一个 Saga 只能有一条预留记录。
    -- 这个唯一性【本身就是幂等保证】—— 重复的预留请求会撞主键，天然被拦住
    saga_id     VARCHAR(36)  PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED', 'RELEASED', 'CONFIRMED'))
);

CREATE INDEX idx_stock_reservations_order ON stock_reservations (order_id);
-- 超时补偿任务用：扫描长时间停留在 RESERVED 状态的预留（Saga 卡死了）
CREATE INDEX idx_stock_reservations_stale ON stock_reservations (updated_at) WHERE status = 'RESERVED';

CREATE TABLE stock_reservation_items (
    id           BIGSERIAL PRIMARY KEY,
    saga_id      VARCHAR(36) NOT NULL REFERENCES stock_reservations(saga_id) ON DELETE CASCADE,
    menu_item_id BIGINT      NOT NULL,
    quantity     INTEGER     NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_reservation_items_saga ON stock_reservation_items (saga_id);

-- ============================================================================
-- Outbox + 幂等去重（来自 messaging 共享模块）
--
-- 每个服务都有自己的这两张表，因为它们必须和【该服务自己的业务表】
-- 在同一个数据库里，才能被同一个本地事务覆盖。
-- 放到一个公共库里就完全失去意义了 —— 那又变回跨库双写了。
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

-- relay 的核心查询索引。部分索引至关重要：
-- 已发送的记录会累积到百万级，但 relay 只关心未发送的那几条。
-- 有了 WHERE sent_at IS NULL，这个索引永远只有几十行，常驻内存，查询是微秒级
CREATE INDEX idx_outbox_unsent ON outbox_events (id) WHERE sent_at IS NULL;
-- 清理任务用
CREATE INDEX idx_outbox_sent_at ON outbox_events (sent_at) WHERE sent_at IS NOT NULL;

CREATE TABLE processed_events (
    event_id     VARCHAR(36)  NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- 复合主键：同一事件可以被【不同的消费者组】各处理一次。
    -- 如果主键只有 event_id，第二个消费者组会误判为"已处理"而静默跳过
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_time ON processed_events (processed_at);
