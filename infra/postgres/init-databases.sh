#!/bin/bash
# Postgres 容器首次启动时自动执行（放在 /docker-entrypoint-initdb.d/ 下的脚本会被依次运行）。
#
# 为每个服务创建【独立的 database】—— 这是 database-per-service 模式。
#
# 为什么这件事是整个微服务架构的地基：
#   如果所有服务共用一个库，你得到的是「分布式单体」：
#     - 一个服务改表结构，其他服务可能在运行时炸掉，因为没人知道谁在读那张表
#     - 服务之间会不由自主地写跨表 JOIN，边界瞬间消失
#     - 数据库成了唯一的扩容瓶颈，服务拆得再细也没用
#     - 最关键：还能用一个本地事务包住所有操作 —— 那 Saga 就完全没有存在意义了
#
#   反过来说：正因为每个服务有自己的库，跨服务的一致性才【无法】靠本地事务实现，
#   这才逼出了 Saga + 补偿事务。我的 Saga 不是为了炫技，是被这个约束逼出来的。
#
# 代价（要能主动说出来）：
#   - 没法跨服务 JOIN。想要「订单 + 菜品名」就得在事件里冗余菜品名（见 OrderLineItem）
#   - 没有跨服务外键约束，引用完整性要靠应用层和对账任务保证
#   - 查询「所有服务的数据」需要 CQRS 读模型或数据仓库
#
# 生产环境这里会是 5 个独立的 RDS 实例（独立扩容、独立备份、故障隔离）；
# 本地开发用一个实例开多个 database，逻辑隔离等价，省内存。
set -e

create_db() {
  local db=$1
  echo "  创建数据库: $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
      CREATE DATABASE $db;
      GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;
EOSQL
}

echo "==> 初始化 OrderStream 各服务的独立数据库"
create_db auth_db
create_db menu_db
create_db order_db
create_db payment_db
create_db notification_db
echo "==> 完成：5 个服务库已就绪"
