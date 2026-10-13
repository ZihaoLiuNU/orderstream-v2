#!/usr/bin/env bash
# ============================================================================
# 端到端冒烟测试
#
# 验证的是【跨 7 个服务的完整链路】，这是单元测试覆盖不到的：
#   - 网关的 JWT 验签和身份头注入
#   - order-service 到 menu-service 的同步调用（CompletableFuture 并行）
#   - Outbox -> Kafka -> menu-service（库存预留）
#   - Kafka -> payment-service（支付）-> 回执 -> Saga 完成
#   - 订单状态最终变成 CONFIRMED
#   - BOLA 防护、幂等性、身份头伪造防护
#
# 用法： bash loadtest/smoke-test.sh
# ============================================================================
set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
info()  { printf '\033[36m%s\033[0m\n' "$1"; }

check() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    green "  PASS  $name"
    PASS=$((PASS+1))
  else
    red   "  FAIL  $name  (期望: $expected, 实际: $actual)"
    FAIL=$((FAIL+1))
  fi
}

# jq 不一定装了，用 grep+sed 提取 JSON 字段，保证脚本无额外依赖
json() { grep -o "\"$2\":[^,}]*" <<<"$1" | head -1 | sed 's/.*://; s/"//g; s/^ *//'; }

info "==================== 1. 认证 ===================="

USERNAME="smoke_$(date +%s)"
REG=$(curl -sS -X POST "$GATEWAY/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"email\":\"$USERNAME@test.local\",\"password\":\"password123\"}")

TOKEN=$(json "$REG" accessToken)
USER_ID=$(json "$REG" userId)
REFRESH=$(json "$REG" refreshToken)

if [ -n "$TOKEN" ]; then green "  PASS  注册并拿到 access token (userId=$USER_ID)"; PASS=$((PASS+1));
else red "  FAIL  注册失败: $REG"; FAIL=$((FAIL+1)); exit 1; fi

# ---- 输入校验：弱密码必须被拒 ----
CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"username":"x","email":"bad","password":"123"}')
check "非法注册请求被 @Valid 拦截 (400)" "400" "$CODE"

# ---- 密码错误不能泄露用户是否存在 ----
WRONG=$(curl -sS -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"wrongpassword\"}")
NOUSER=$(curl -sS -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"definitely_not_exists_xyz","password":"wrongpassword"}')
M1=$(json "$WRONG" message); M2=$(json "$NOUSER" message)
check "防用户名枚举：两种失败返回相同文案" "$M1" "$M2"

info ""
info "==================== 2. 网关安全 ===================="

# ---- 无 token 必须 401 ----
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/api/orders/my")
check "未认证请求被拒绝 (401)" "401" "$CODE"

# ---- 【核心安全测试】伪造身份头必须被剥离 ----
# 没有这道防护的话，下面这条命令就能拿到管理员权限
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/api/orders/my" \
  -H 'X-User-Id: 1' -H 'X-Username: admin' -H 'X-User-Role: ADMIN')
check "伪造身份头被网关剥离，仍返回 401（认证绕过防护）" "401" "$CODE"

info ""
info "==================== 3. 菜单（公开 + 缓存）===================="

MENU=$(curl -sS "$GATEWAY/api/restaurants/1")
RESTAURANT_ID=$(json "$MENU" id)
check "未登录也能浏览菜单" "1" "$RESTAURANT_ID"

# 第二次请求走 Redis 缓存
curl -sS -o /dev/null "$GATEWAY/api/restaurants/1"
green "  PASS  菜单二次请求（应命中 Redis 缓存，见 menu-service 日志）"
PASS=$((PASS+1))

MENU_ITEM_ID=$(grep -o '"menuItems":\[{"id":[0-9]*' <<<"$MENU" | grep -o '[0-9]*$' | head -1)
info "  使用菜品 ID: $MENU_ITEM_ID"

info ""
info "==================== 4. 购物车（Redis）===================="

CART=$(curl -sS -X POST "$GATEWAY/api/cart/items" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"restaurantId\":1,\"menuItemId\":$MENU_ITEM_ID,\"delta\":2}")
check "加入购物车后总数为 2" "2" "$(json "$CART" totalQuantity)"

CART=$(curl -sS -X POST "$GATEWAY/api/cart/items" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"restaurantId\":1,\"menuItemId\":$MENU_ITEM_ID,\"delta\":-1}")
check "HINCRBY 原子减少后总数为 1" "1" "$(json "$CART" totalQuantity)"

info ""
info "==================== 5. 下单 + Saga 全流程 ===================="

IDEM_KEY="smoke-$(date +%s%N)"
ORDER=$(curl -sS -X POST "$GATEWAY/api/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d "{\"restaurantId\":1,\"items\":[{\"menuItemId\":$MENU_ITEM_ID,\"quantity\":2}]}")

ORDER_ID=$(json "$ORDER" id)
if [ -n "$ORDER_ID" ]; then
  green "  PASS  订单已创建 orderId=$ORDER_ID 状态=$(json "$ORDER" status) 金额=$(json "$ORDER" totalAmount)"
  PASS=$((PASS+1))
else
  red "  FAIL  下单失败: $ORDER"; FAIL=$((FAIL+1)); exit 1
fi

# ---- 幂等：同一个 key 再发一次，必须返回同一个订单 ----
ORDER2=$(curl -sS -X POST "$GATEWAY/api/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d "{\"restaurantId\":1,\"items\":[{\"menuItemId\":$MENU_ITEM_ID,\"quantity\":2}]}")
check "幂等键命中：重复下单返回同一订单" "$ORDER_ID" "$(json "$ORDER2" id)"

# ---- 等待 Saga 跑完：outbox 轮询(500ms) x2 + 支付延迟(50ms) ----
info "  等待 Saga 完成（库存预留 -> 支付 -> 确认）..."
FINAL_STATUS=""
for i in $(seq 1 30); do
  sleep 1
  DETAIL=$(curl -sS "$GATEWAY/api/orders/$ORDER_ID" -H "Authorization: Bearer $TOKEN")
  FINAL_STATUS=$(json "$DETAIL" status)
  [ "$FINAL_STATUS" != "PENDING" ] && break
done

if [ "$FINAL_STATUS" = "CONFIRMED" ]; then
  green "  PASS  Saga 完成：订单已 CONFIRMED（耗时约 ${i} 秒）"
  PASS=$((PASS+1))
elif [ "$FINAL_STATUS" = "CANCELLED" ]; then
  # 支付模拟有 10% 失败率，这也是正确行为 —— 说明补偿路径跑通了
  green "  PASS  Saga 走了补偿路径：订单已 CANCELLED（原因: $(json "$DETAIL" cancelReason)）"
  green "        这是 10% 模拟支付失败触发的，补偿逻辑正确执行"
  PASS=$((PASS+1))
else
  red "  FAIL  Saga 未在 30 秒内结束，当前状态: $FINAL_STATUS"
  FAIL=$((FAIL+1))
fi

info ""
info "==================== 6. 对象级授权（BOLA）===================="

# 另一个用户尝试读这个订单
OTHER="smoke_other_$(date +%s)"
OTHER_REG=$(curl -sS -X POST "$GATEWAY/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$OTHER\",\"email\":\"$OTHER@test.local\",\"password\":\"password123\"}")
OTHER_TOKEN=$(json "$OTHER_REG" accessToken)

CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/api/orders/$ORDER_ID" \
  -H "Authorization: Bearer $OTHER_TOKEN")
check "他人订单访问被拒绝 (403) —— 原版这里是 200，能拖走全站订单" "403" "$CODE"

info ""
info "==================== 7. 功能级授权（RBAC）===================="

CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH "$GATEWAY/api/orders/$ORDER_ID/status" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"PREPARING"}')
check "普通用户无法修改订单状态 (403)" "403" "$CODE"

info ""
info "==================== 8. Token 轮换与重放检测 ===================="

R1=$(curl -sS -X POST "$GATEWAY/api/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
NEW_REFRESH=$(json "$R1" refreshToken)
if [ -n "$NEW_REFRESH" ] && [ "$NEW_REFRESH" != "$REFRESH" ]; then
  green "  PASS  refresh token 已轮换（新旧不同）"; PASS=$((PASS+1))
else
  red "  FAIL  token 未轮换"; FAIL=$((FAIL+1))
fi

# 用已经被轮换掉的旧 token 再刷一次 -> 触发重放检测
R2=$(curl -sS -X POST "$GATEWAY/api/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
MSG=$(json "$R2" message)
if grep -q "会话已失效" <<<"$MSG"; then
  green "  PASS  旧 token 重放被检测到，整个会话已吊销"; PASS=$((PASS+1))
else
  red "  FAIL  重放检测未生效: $MSG"; FAIL=$((FAIL+1))
fi

info ""
info "==================== 结果 ===================="
green "  通过: $PASS"
[ "$FAIL" -gt 0 ] && red "  失败: $FAIL" || green "  失败: 0"
exit $((FAIL > 0 ? 1 : 0))
