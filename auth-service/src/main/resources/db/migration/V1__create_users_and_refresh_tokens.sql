-- Flyway 迁移脚本。文件名格式 V<版本>__<描述>.sql，版本号必须递增且不可重复。
--
-- Flyway 在 flyway_schema_history 表里记录每个脚本的【校验和】。
-- 一旦某个脚本被执行过，就绝不能再修改它 —— 改了校验和对不上，启动直接失败。
-- 这个看似严格的约束正是它的价值：它保证所有环境(本地/CI/预发/生产)的 schema
-- 演进路径完全一致。要改已上线的表，只能新增 V2__xxx.sql。

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    email       VARCHAR(255) NOT NULL,
    -- BCrypt 哈希固定 60 字符。存哈希不存明文，且 BCrypt 自带 salt（就在这 60 个字符里），
    -- 所以不需要单独的 salt 列 —— 这是 BCrypt 相比 MD5/SHA 的关键优势之一。
    --
    -- 【为什么是 VARCHAR(60) 而不是 CHAR(60)，尽管长度是固定的】
    --   直觉上"定长数据用 CHAR"，但在 Postgres 里这是错的，有两个理由：
    --
    --   1) CHAR 会用空格把值【补齐】到声明长度，而且比较时的行为很反直觉：
    --      'abc' 和 'abc   ' 在 CHAR 语义下判定为相等。
    --      如果某天哈希算法换成更短的格式，末尾的填充空格会让比较静默出错。
    --
    --   2) Postgres 官方文档明确说明：CHAR 在 Postgres 里【没有任何性能优势】，
    --      反而因为要处理填充而略慢。这和 Oracle/MySQL 的情况不同，
    --      很多人是从那边带过来的习惯。
    --
    --   另外 JPA 的 @Column(length=60) 默认映射到 VARCHAR，
    --   用 CHAR 会导致 ddl-auto=validate 在启动时直接报
    --   "found [bpchar], but expecting [varchar]" 而启动失败 ——
    --   这是我第一次跑起来时真实踩到的坑，也恰恰说明 validate 起了作用：
    --   它在【部署阶段】就暴露了 schema 和代码的不一致，
    --   而不是等到某个查询跑到这个字段时才在半夜炸掉。
    password    VARCHAR(60)  NOT NULL,
    role        VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email    UNIQUE (email),
    CONSTRAINT ck_users_role     CHECK (role IN ('USER', 'ADMIN'))
);

-- 为什么用数据库唯一约束，而不是在代码里「先 SELECT 看看存不存在，再 INSERT」：
-- 那是典型的 check-then-act 竞态。两个请求同时注册同一个用户名，
-- 两边的 SELECT 都返回「不存在」，然后两边都 INSERT 成功 —— 出现重复用户。
-- 只有数据库的唯一索引能在并发下提供真正的原子性保证。
-- 代码里仍然先查一次，只是为了返回友好的错误信息；真正的正确性靠约束兜底。

CREATE TABLE refresh_tokens (
    id           BIGSERIAL PRIMARY KEY,
    -- 存哈希不存原文：数据库泄露时，攻击者拿到的哈希无法直接当 token 用。
    -- 和存密码同理 —— refresh token 的权限等同于长期密码，必须按凭据对待
    token_hash   VARCHAR(64)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    -- 同一条「登录会话」派生出的所有 token 共享一个 family_id。
    -- 检测到 token 重放时，整个 family 一起吊销（见 AuthService.refresh 的注释）
    family_id    UUID         NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);

-- 刷新时按 token_hash 精确查找（已有唯一索引覆盖）；
-- 吊销整个 family 时按 family_id 批量更新，需要这个索引
CREATE INDEX idx_refresh_tokens_family  ON refresh_tokens (family_id);
-- 定时清理过期 token 的任务按 expires_at 扫描
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
