-- ============================================================================
-- notification-service 的独立数据库 (notification_db)
-- ============================================================================

CREATE TABLE notifications (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    order_id     BIGINT       NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    title        VARCHAR(200) NOT NULL,
    body         VARCHAR(1000) NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    failure_reason VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at      TIMESTAMPTZ,

    CONSTRAINT ck_notification_channel CHECK (channel IN ('PUSH', 'SMS', 'EMAIL')),
    CONSTRAINT ck_notification_status  CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- 用户查自己的通知列表：复合索引让过滤和排序一次完成，执行计划里没有 Sort 节点
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);

-- 重试任务扫描发送失败的通知。
-- 部分索引：FAILED 的通知是极少数，索引里只有这几条，扫描成本接近于零。
-- 如果建全列索引，每次扫描都要过滤掉几千万条已成功的记录
CREATE INDEX idx_notifications_retry ON notifications (created_at) WHERE status = 'FAILED';

-- ============================================================================
-- 幂等去重
--
-- 【注意这个服务没有 outbox 表】
--   它是纯消费者：只订阅 order.status.changed，不产生任何领域事件。
--   没有"发事件"的需求，自然不需要 outbox。
--
--   这体现了一个原则：基础设施组件要按【实际需要】引入，
--   而不是"别的服务都有所以我也加一个"。
--   多一张表就多一份维护成本、多一个可能出问题的地方。
-- ============================================================================
CREATE TABLE processed_events (
    event_id     VARCHAR(36)  NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_time ON processed_events (processed_at);
