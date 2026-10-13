-- ============================================================================
-- payment-service 的独立数据库 (payment_db)
-- ============================================================================

CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT         NOT NULL,
    user_id         BIGINT         NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)    NOT NULL,

    -- ========================================================================
    -- 【幂等键上的唯一索引 —— 防止重复扣款的最后一道、也是唯一可靠的一道防线】
    -- ========================================================================
    --
    -- Kafka 是 at-least-once，"支付请求"这条消息【必然】会有重复投递的时候：
    --   - 消费者处理成功后、提交 offset 前崩溃
    --   - 消费者处理超时被踢出消费者组，分区重新分配
    --   - Outbox relay 发送成功但标记 sent_at 前崩溃
    --
    -- 应用层的"先查一下有没有处理过"在并发下是有竞态窗口的：
    --   两个线程同时查，都发现没有，都去扣款 -> 扣了两次。
    --
    -- 只有数据库的唯一约束能在并发下提供真正的原子性保证。
    -- 第二个 INSERT 会直接失败，我们捕获这个失败并返回第一次的结果。
    --
    -- 这和 Stripe / Square 对外暴露的 Idempotency-Key 是同一个机制。
    -- 任何有副作用且可能被重试的操作，都必须有这样一个唯一键。
    idempotency_key VARCHAR(64)    NOT NULL,

    failure_reason  VARCHAR(500),
    -- 外部支付网关返回的交易号。真实环境下退款必须引用它
    external_txn_id VARCHAR(100),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_payments_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'REFUNDED'))
);

-- 按订单查支付记录（客服、对账）
CREATE INDEX idx_payments_order ON payments (order_id);
-- 用户查自己的支付记录，复合索引让过滤和排序一次搞定
CREATE INDEX idx_payments_user_created ON payments (user_id, created_at DESC);

-- ============================================================================
-- 退款记录
-- ============================================================================
--
-- 【退款是一笔【新的、方向相反的】交易，不是把原支付记录改掉或删掉】
--
-- 这是财务系统的铁律：账目只能追加，不能修改历史（append-only ledger）。
-- 理由：
--   1) 审计：必须能回答"这笔钱什么时候收的、什么时候退的、谁操作的"。
--      把 payments.status 直接改成 REFUNDED 会丢失"原来收了多少、什么时候收的"。
--   2) 部分退款：一笔 100 元的支付可能分两次退 30 和 70。
--      改状态的模型根本表达不了这种情况。
--   3) 对账：和支付网关对账时，双方比对的是【交易流水】，不是当前状态。
--
-- 所以 payments 表的记录一旦写入就不再改金额，退款另开一张表。
CREATE TABLE refunds (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      BIGINT         NOT NULL REFERENCES payments(id),
    order_id        BIGINT         NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    reason          VARCHAR(500),
    -- 同样需要幂等：退款消息重复投递不能退两次钱
    idempotency_key VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_refunds_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);

-- ============================================================================
-- Outbox + 幂等去重
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
