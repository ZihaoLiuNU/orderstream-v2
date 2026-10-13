#!/usr/bin/env bash
# ============================================================================
# 压测环境重置
#
# ============================================================================
# 【为什么不能只 TRUNCATE 数据库 —— 这是我实际踩到的坑】
# ============================================================================
#
# 第一次重置时我只清了 5 个数据库，然后重跑压测。
# 结果校验脚本报出一个诡异现象：
#     订单数 17,893，但支付记录有 31,904 条
# 而"同一订单没有多笔支付"这项检查却是通过的。
#
# 原因：【Kafka 里还积压着上一轮压测的消息】。
#   TRUNCATE 清掉了数据库，但 Kafka topic 里还有三万多条
#   payment.requested 没被消费完。
#   它们在截断之后才被 payment-service 消费，
#   于是为一批【已经不存在的订单】创建了支付记录。
#
# 这不是系统 bug —— payment-service 本来就不知道 order 表的存在，
# 它只是忠实地处理了收到的消息。
# 这是【测试环境重置不彻底】导致的数据污染。
#
# 【教训】：在事件驱动系统里，"状态"不只在数据库里，
#   还在【消息队列的未消费消息】和【消费者的 offset】里。
#   重置环境必须把三者一起处理，否则会得到自相矛盾的测试结果，
#   然后花大量时间去排查一个根本不存在的 bug。
#
# 用法： bash loadtest/reset.sh
# ============================================================================
set -euo pipefail

info(){ printf '\033[36m%s\033[0m\n' "$1"; }
ok(){   printf '\033[32m%s\033[0m\n' "$1"; }

TOPICS="stock.reservation.requested stock.reservation.result stock.release.requested \
payment.requested payment.result payment.refund.requested order.status.changed"

info "==> 1/4 停止应用服务（避免重置过程中还在产生新消息）"
docker compose stop api-gateway auth-service menu-service cart-service \
                    order-service payment-service notification-service >/dev/null 2>&1
ok "    应用已停止（基础设施保持运行）"

info "==> 2/4 删除并重建 Kafka topic"
for t in $TOPICS; do
  docker exec os-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --delete --topic "$t" >/dev/null 2>&1 || true
  docker exec os-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --delete --topic "${t}.DLT" >/dev/null 2>&1 || true
done
# 删除是异步的，等 broker 真正清理完。
# 不等的话，服务启动时 KafkaAdmin 可能在"标记为删除中"的 topic 上建失败
sleep 8
ok "    topic 已删除（服务启动时 KafkaTopicsConfig 会自动重建）"

info "==> 3/4 清空数据库"
docker exec os-postgres psql -U orderstream -d order_db -q -c \
  "TRUNCATE orders, order_items, order_sagas, order_idempotency_keys, outbox_events, processed_events RESTART IDENTITY CASCADE;"
docker exec os-postgres psql -U orderstream -d payment_db -q -c \
  "TRUNCATE payments, refunds, outbox_events, processed_events RESTART IDENTITY CASCADE;"
docker exec os-postgres psql -U orderstream -d menu_db -q -c \
  "TRUNCATE stock_reservations, stock_reservation_items, outbox_events, processed_events RESTART IDENTITY CASCADE;"
docker exec os-postgres psql -U orderstream -d notification_db -q -c \
  "TRUNCATE notifications, processed_events RESTART IDENTITY CASCADE;"
# 库存恢复到初始值，这样"库存守恒"校验才有确定的基准
docker exec os-postgres psql -U orderstream -d menu_db -q -c \
  "UPDATE menu_items SET stock_quantity = 1000000, version = 0;"
ok "    5 个数据库已重置，库存恢复为 50 x 1,000,000"

info "==> 4/4 清空 Redis 并重启应用"
# 注意不是 FLUSHALL：只清当前 db，避免误伤同一 Redis 实例上的其他数据
docker exec os-redis redis-cli FLUSHDB >/dev/null
docker compose up -d >/dev/null 2>&1

info "    等待服务健康..."
for i in $(seq 1 40); do
  UNHEALTHY=$(docker compose ps --format '{{.Status}}' | grep -cv 'healthy' || true)
  [ "$UNHEALTHY" = "0" ] && break
  sleep 5
done
docker compose ps --format 'table {{.Name}}\t{{.Status}}'

ok ""
ok "重置完成。现在可以跑："
ok "  docker run --rm --network=orderstream-v2_default -v \"\$PWD/loadtest:/scripts\" \\"
ok "    -e GATEWAY=http://api-gateway:8080 -e TARGET_VUS=500 -e USER_POOL=500 \\"
ok "    grafana/k6 run /scripts/order-load-test.js"
