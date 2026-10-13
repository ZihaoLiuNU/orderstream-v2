#!/usr/bin/env bash
# ============================================================================
# 压测后的数据正确性校验
#
# ============================================================================
# 【为什么这个脚本比压测本身更重要】
# ============================================================================
#
# 一个分布式系统在高并发下"跑得很快"是廉价的 —— 丢掉一半消息、
# 超卖一批库存、重复扣几笔款，都能让 TPS 数字很好看。
#
# 真正难的是【又快又对】。所以压测报告必须包含正确性验证，
# 否则那些延迟和吞吐数字没有任何意义。
#
# 这个脚本检查 6 个不变量（invariant），每一个对应一类真实的生产事故：
#   1. 没有卡死的 Saga         —— 对应"订单永久卡在处理中"
#   2. 没有丢失的事件           —— 对应"支付成功但订单没确认"
#   3. 没有重复扣款             —— 对应用户被扣两次钱
#   4. 库存守恒                 —— 对应超卖 / 库存凭空消失
#   5. Saga 状态与订单状态一致   —— 对应"订单显示已取消但钱没退"
#   6. 没有堆积的死信           —— 对应消息被静默丢弃
#
# 用法： bash loadtest/verify-correctness.sh
# ============================================================================
set -uo pipefail

PSQL="docker exec os-postgres psql -U orderstream -t -A -F| "
PASS=0; FAIL=0

green(){ printf '\033[32m%s\033[0m\n' "$1"; }
red(){   printf '\033[31m%s\033[0m\n' "$1"; }
info(){  printf '\033[36m%s\033[0m\n' "$1"; }

q() { docker exec os-postgres psql -U orderstream -d "$1" -t -A -c "$2" 2>/dev/null | tr -d '\r'; }

assert_zero() {
  local name="$1" value="$2"
  if [ "${value:-x}" = "0" ]; then green "  PASS  $name"; PASS=$((PASS+1));
  else red "  FAIL  $name  (实际: $value)"; FAIL=$((FAIL+1)); fi
}

assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then green "  PASS  $name  ($actual)"; PASS=$((PASS+1));
  else red "  FAIL  $name  (期望: $expected, 实际: $actual)"; FAIL=$((FAIL+1)); fi
}

# ============================================================================
info "==================== 总量 ===================="
ORDERS=$(q order_db "SELECT count(*) FROM orders;")
SAGAS=$(q order_db "SELECT count(*) FROM order_sagas;")
PAYMENTS=$(q payment_db "SELECT count(*) FROM payments;")
RESERVATIONS=$(q menu_db "SELECT count(*) FROM stock_reservations;")

info "  订单数          : $ORDERS"
info "  Saga 数         : $SAGAS"
info "  支付记录数      : $PAYMENTS"
info "  库存预留记录数  : $RESERVATIONS"

# 【不变量】每个订单必须恰好有一个 Saga。
# 不相等说明订单创建和 Saga 创建不在同一个事务里 —— 那整个设计就是错的
assert_eq "订单数 == Saga 数（同事务创建）" "$ORDERS" "$SAGAS"

# ============================================================================
info ""
info "==================== 1. 没有卡死的 Saga ===================="
# Saga 必须全部到达终态。任何一个停留在 STOCK_PENDING/PAYMENT_PENDING
# 都意味着有订单永久卡在"处理中"，用户既收不到餐也退不了款
STUCK=$(q order_db "SELECT count(*) FROM order_sagas WHERE state IN ('STOCK_PENDING','PAYMENT_PENDING');")
assert_zero "没有 Saga 停留在进行中状态" "$STUCK"

FAILED=$(q order_db "SELECT count(*) FROM order_sagas WHERE state = 'FAILED';")
assert_zero "没有 FAILED 状态的 Saga（需人工对账）" "$FAILED"

COMPLETED=$(q order_db "SELECT count(*) FROM order_sagas WHERE state='COMPLETED';")
COMPENSATED=$(q order_db "SELECT count(*) FROM order_sagas WHERE state='COMPENSATED';")
info "  COMPLETED(成功): $COMPLETED   COMPENSATED(已补偿): $COMPENSATED"
info "  补偿率约 $(( COMPENSATED * 100 / (ORDERS > 0 ? ORDERS : 1) ))% —— 应接近配置的 10% 模拟支付失败率"

# ============================================================================
info ""
info "==================== 2. 没有丢失的事件 ===================="
# Outbox 里不能有未发送的积压。有积压说明 relay 挂了或 Kafka 不可用，
# 下游正在静默地失去数据
for db in order_db payment_db menu_db; do
  UNSENT=$(q $db "SELECT count(*) FROM outbox_events WHERE sent_at IS NULL;")
  assert_zero "$db 的 outbox 无积压" "$UNSENT"
done

# ============================================================================
info ""
info "==================== 3. 没有重复扣款 ===================="
# 幂等键上有唯一索引，理论上不可能重复。但显式验证一遍 ——
# 这是整个系统最不能出错的不变量，值得用最笨的方式再确认一次
DUP_PAY=$(q payment_db "SELECT count(*) FROM (SELECT idempotency_key FROM payments GROUP BY idempotency_key HAVING count(*) > 1) d;")
assert_zero "没有重复的支付幂等键" "$DUP_PAY"

DUP_ORDER_PAY=$(q payment_db "SELECT count(*) FROM (SELECT order_id FROM payments GROUP BY order_id HAVING count(*) > 1) d;")
assert_zero "同一订单没有多笔支付" "$DUP_ORDER_PAY"

# 支付金额必须和订单金额一致 —— 跨库无法 JOIN，所以比对总额
# 【注意 o.total_amount 的表前缀不能省】
# orders 和 order_sagas 【都有】 total_amount 列，不加前缀 Postgres 会报
#   ERROR: column reference "total_amount" is ambiguous
# 而我的 q() 把 stderr 丢掉了，所以表现成"查询返回空字符串"，
# 校验项莫名其妙失败。
#
# 【这是个关于错误处理的教训】：工具脚本里吞掉 stderr 会让
# "查询出错"和"查询结果为空"变得无法区分，排查成本陡增。
ORDER_SUM=$(q order_db "SELECT COALESCE(sum(o.total_amount),0) FROM orders o JOIN order_sagas s ON s.order_id=o.id WHERE s.state='COMPLETED';")
PAY_SUM=$(q payment_db "SELECT COALESCE(sum(amount),0) FROM payments WHERE status='SUCCEEDED';")
assert_eq "成功订单总额 == 成功支付总额（跨库对账）" "$ORDER_SUM" "$PAY_SUM"

# ============================================================================
info ""
info "==================== 4. 库存守恒 ===================="
# 【最核心的不变量】
#   初始库存 = 当前剩余库存 + 仍被占用的预留量
#
#   种子数据给每个菜品 1,000,000 库存，共 50 个菜品 = 50,000,000
#
#   如果左边 > 右边：库存【凭空消失】了（扣了没还，静默泄漏）
#   如果左边 < 右边：库存【凭空多出】了（还了两次，会导致超卖）
#   两种都是严重的数据损坏。
INITIAL=$((50 * 1000000))

# ============================================================================
# 【必须在【一条 SQL】里同时算出两个数 —— 这是我踩过的一个测量陷阱】
# ============================================================================
#
# 第一版是分两次查询：
#     CURRENT=$(q menu_db "SELECT sum(stock_quantity) FROM menu_items;")
#     HELD=$(q   menu_db "SELECT sum(quantity) FROM ... WHERE status='RESERVED';")
#
# 结果报出 91 个单位的"库存泄漏"，我差点去查代码 bug。
# 实际上逐个菜品对账【全部为 0】，一分不差。
#
# 真正的原因：两条查询之间隔了几十毫秒，而系统【还在处理】——
# 后台的 StuckSagaSweeper 正在补偿早期积压的 Saga，
# 每分钟释放几百份库存。
# 于是第一条查询看到的是时刻 T1 的库存，第二条看到的是 T2 的预留，
# 两个不同时刻的快照相减，当然对不上。
#
# 【教训】：守恒类不变量（A + B == C）必须在【同一个一致性快照】里测量。
#   Postgres 的单条语句天然看到一致快照（MVCC），
#   所以把两个聚合放进一条 SQL 就解决了。
#
#   这个陷阱在分布式系统的验证里非常普遍：
#   你以为在验证系统，其实在验证自己的测量方法。
#   任何"偶发的、量很小的不一致"，先怀疑测量，再怀疑系统。
# ============================================================================
read -r CURRENT HELD <<< "$(docker exec os-postgres psql -U orderstream -d menu_db -t -A -F' ' -c "
  SELECT
    (SELECT COALESCE(sum(stock_quantity),0) FROM menu_items),
    (SELECT COALESCE(sum(i.quantity),0)
       FROM stock_reservation_items i
       JOIN stock_reservations r ON r.saga_id = i.saga_id
      WHERE r.status = 'RESERVED');" 2>/dev/null | tr -d '')"

TOTAL=$((CURRENT + HELD))

info "  初始库存        : $INITIAL"
info "  当前剩余        : $CURRENT"
info "  预留占用(RESERVED): $HELD"
info "  剩余+占用       : $TOTAL"
assert_eq "库存守恒：剩余 + 占用 == 初始（单快照测量）" "$INITIAL" "$TOTAL"

# 再做一次【逐个菜品】的对账。总量相等有可能是"这个多了那个少了"互相抵消，
# 逐项对账才能真正排除这种情况 —— 聚合会掩盖分布上的错误
PER_ITEM_DRIFT=$(q menu_db "
  WITH held AS (
    SELECT i.menu_item_id, SUM(i.quantity) AS qty
      FROM stock_reservation_items i
      JOIN stock_reservations r ON r.saga_id = i.saga_id
     WHERE r.status = 'RESERVED'
     GROUP BY i.menu_item_id)
  SELECT count(*) FROM menu_items m
  LEFT JOIN held h ON h.menu_item_id = m.id
  WHERE m.stock_quantity + COALESCE(h.qty,0) <> 1000000;")
assert_zero "逐个菜品对账均无偏差（排除互相抵消）" "$PER_ITEM_DRIFT"

# 绝不能出现负库存 —— 那是超卖的直接证据
NEG=$(q menu_db "SELECT count(*) FROM menu_items WHERE stock_quantity < 0;")
assert_zero "没有负库存（无超卖）" "$NEG"

# 已释放的预留必须已经把库存还回去了
RELEASED=$(q menu_db "SELECT count(*) FROM stock_reservations WHERE status='RELEASED';")
info "  已释放的预留(补偿执行次数): $RELEASED"

# ============================================================================
info ""
info "==================== 5. Saga 与订单状态一致 ===================="
# COMPLETED 的 Saga 对应的订单不能是 CANCELLED，反之亦然。
# 不一致意味着"订单显示已取消但钱没退"或"订单已确认但 Saga 认为失败了"
INCONSISTENT=$(q order_db "
  SELECT count(*) FROM orders o JOIN order_sagas s ON s.order_id = o.id
  WHERE (s.state='COMPLETED'   AND o.status NOT IN ('CONFIRMED','PREPARING','DELIVERED'))
     OR (s.state='COMPENSATED' AND o.status <> 'CANCELLED');")
assert_zero "Saga 状态与订单状态完全一致" "$INCONSISTENT"

# 被取消的订单必须有取消原因 —— 没有原因的取消是无法向用户解释的
NO_REASON=$(q order_db "SELECT count(*) FROM orders WHERE status='CANCELLED' AND (cancel_reason IS NULL OR cancel_reason='');")
assert_zero "所有取消的订单都有原因" "$NO_REASON"

# ============================================================================
info ""
info "==================== 6. 死信队列 ===================="
DLT_TOTAL=0
for t in stock.reservation.requested stock.reservation.result stock.release.requested payment.requested payment.result order.status.changed; do
  N=$(docker exec os-kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
        --bootstrap-server localhost:9092 --topic "${t}.DLT" 2>/dev/null \
        | awk -F: '{s+=$3} END {print s+0}')
  DLT_TOTAL=$((DLT_TOTAL + ${N:-0}))
done
assert_zero "所有死信队列为空（没有消息被丢弃）" "$DLT_TOTAL"

# ============================================================================
info ""
info "==================== 结果 ===================="
green "  通过: $PASS"
if [ "$FAIL" -gt 0 ]; then red "  失败: $FAIL"; else green "  失败: 0"; fi
exit $((FAIL > 0 ? 1 : 0))
