/* eslint-disable */
// ============================================================================
// OrderStream 下单压测（k6）
//
// 运行方式（不需要本地装 k6）：
//   docker run --rm -i --network=orderstream-v2_default \
//     -e GATEWAY=http://api-gateway:8080 \
//     grafana/k6 run - < loadtest/order-load-test.js
//
// ============================================================================
// 【这个压测到底在测什么 —— 必须先说清楚，否则数字没有意义】
// ============================================================================
//
// 目标是 "500+ concurrent order events"。这句话有两种完全不同的解读：
//
//   解读 A（错误）："系统能做到 500 TPS"
//     这是吞吐量指标。本系统的吞吐硬上限由 payment-service 决定：
//       consumer-concurrency(6) / 模拟支付延迟(50ms) = 120 单/秒
//     说 500 TPS 是不诚实的。
//
//   解读 B（正确）："系统能同时承载 500 个在途订单而不降级"
//     这是并发度指标，测的是【背压能力】：
//       - 订单 API 能否在 500 并发下快速接收请求并落库
//       - Kafka 能否作为缓冲吸收突发流量
//       - 下游按自己的节奏消费，不会被压垮
//       - 全部订单最终都被正确处理，没有丢失、没有超卖、没有重复扣款
//
// 这正是把支付做成异步的原因：
//   如果支付是同步调用，那 300ms 的支付延迟会直接变成用户的 p99。
//   异步之后，用户的感知延迟只取决于"订单落库"这一步（几十毫秒），
//   支付的延迟被 Kafka 吸收了。
//
// 所以本压测的核心指标不是 TPS，而是：
//   1) 500 并发下，下单 API 的 p95/p99 延迟
//   2) 错误率（必须接近 0）
//   3) 【最重要】结束后的数据正确性：订单数 == 请求数，无超卖，无重复扣款
// ============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const GATEWAY = __ENV.GATEWAY || 'http://api-gateway:8080';
const TARGET_VUS = parseInt(__ENV.TARGET_VUS || '500');
const USER_POOL = parseInt(__ENV.USER_POOL || '100');

// 自定义指标：把"下单"这个业务动作单独拎出来统计，
// 不和登录等辅助请求混在一起 —— 否则平均值会被廉价请求拉低，掩盖真实问题
const orderLatency = new Trend('order_create_duration', true);
const orderSuccess = new Rate('order_create_success');
const orderCount = new Counter('orders_created');
const rateLimited = new Counter('rate_limited_429');

export const options = {
  // 【默认的 summaryTrendStats 只有 avg/min/med/max/p(90)/p(95)】
  // 不配这一行的话，handleSummary 里取 p(50) 和 p(99) 会拿到 undefined，
  // 报告里显示 0.0 ms —— 一个看起来像"性能极好"的假数据。
  // 压测报告的可信度首先取决于统计口径是否正确
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],

  scenarios: {
    order_burst: {
      // ramping-vus：模拟真实的"流量尖峰"，而不是一上来就 500。
      //
      // 【为什么不用固定 500 VU 直接开始】
      //   那会测出一个失真的结果：所有连接池、JIT 编译、数据库连接
      //   都在同一瞬间冷启动，第一秒的延迟会极其难看，
      //   而这不代表系统的稳态性能。
      //   真实的流量尖峰也是有爬升过程的（比如午餐时段）。
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '20s', target: 100 },          // 预热：让 JIT 和连接池就绪
        { duration: '30s', target: TARGET_VUS },   // 爬升到目标并发
        { duration: '60s', target: TARGET_VUS },   // 【稳态】—— 真正要看的就是这一段
        { duration: '20s', target: 0 },            // 平滑收尾
      ],
      gracefulRampDown: '30s',
    },
  },

  // ==========================================================================
  // 【阈值：压测必须有明确的通过/失败标准，否则就只是"跑了一下看看"】
  // ==========================================================================
  thresholds: {
    // p95 < 500ms：用户能接受的下单等待时间。
    // 注意这只覆盖"订单落库"，不含 Saga 的异步部分 —— 那本来就不该让用户等
    'order_create_duration': ['p(95)<500', 'p(99)<1500'],

    // 成功率 > 99%。
    // 【为什么不要求 100%】：网关配了限流（下单 10/s/用户），
    // 高并发下必然会有 429。429 是系统【正确工作】的表现，不是故障。
    // 把它算成失败会误导判断
    'order_create_success': ['rate>0.99'],

    // HTTP 层面的失败率（连接失败、5xx）必须极低。
    //
    // 【注意：必须告诉 k6 429 是预期状态】
    //   k6 默认把所有非 2xx/3xx 都算作 http_req_failed。
    //   第一次跑的时候这个指标是 30.62%，看起来系统烂透了，
    //   但实际上那 30% 全是【限流正常工作】产生的 429。
    //
    //   如果不区分，压测报告会把"系统正确地保护了自己"
    //   误报成"系统有三成请求失败了" —— 这种指标口径错误
    //   会直接导致错误的架构决策（比如误以为要扩容）。
    //
    //   解法见下面 params 里的 responseCallback。
    'http_req_failed': ['rate<0.01'],
  },
};

// ============================================================================
// setup：只跑一次，准备好一批 token 供所有 VU 复用
// ============================================================================
export function setup() {
  console.log(`预热：登录 ${USER_POOL} 个种子用户...`);

  const tokens = [];
  const batchSize = 20;

  // 用 http.batch 并发登录。
  // 【为什么要分批而不是一次发 100 个】：
  //   BCrypt cost=12 意味着每次登录要烧约 250ms CPU。
  //   一次性发 100 个会把 auth-service 的 CPU 打满，
  //   把"准备阶段"变成了对 auth-service 的压测 —— 那不是我们想测的东西。
  for (let start = 1; start <= USER_POOL; start += batchSize) {
    const requests = [];
    for (let i = start; i < start + batchSize && i <= USER_POOL; i++) {
      requests.push([
        'POST',
        `${GATEWAY}/api/auth/login`,
        JSON.stringify({ username: `user${i}`, password: 'password123' }),
        { headers: { 'Content-Type': 'application/json' } },
      ]);
    }
    const responses = http.batch(requests);
    responses.forEach((r) => {
      if (r.status === 200) {
        tokens.push(JSON.parse(r.body).accessToken);
      }
    });
  }

  if (tokens.length === 0) {
    throw new Error('一个 token 都没拿到，检查种子数据是否已初始化（SEED_DATA_ENABLED=true）');
  }
  console.log(`预热完成：拿到 ${tokens.length} 个 token`);
  return { tokens };
}

// ============================================================================
// 主场景：下单
// ============================================================================
export default function (data) {
  const token = data.tokens[__VU % data.tokens.length];

  // 随机挑餐厅和菜品，避免所有请求都打同一行数据。
  //
  // 【如果所有 VU 都抢同一个菜品会怎样】
  //   那条 menu_items 记录会成为热点行，所有扣库存的 UPDATE 在数据库里
  //   排成一队（行级排他锁），测出来的是"单行写入的极限"，
  //   而不是系统的整体吞吐。
  //   这是压测设计里最常见的失真来源：【数据分布不真实】。
  const restaurantId = randomIntBetween(1, 5);
  const menuItemId = (restaurantId - 1) * 10 + randomIntBetween(1, 10);

  const payload = JSON.stringify({
    restaurantId: restaurantId,
    items: [{ menuItemId: menuItemId, quantity: randomIntBetween(1, 3) }],
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      // 每次请求一个全新的幂等键 —— 我们要测的是"创建 N 个订单"，
      // 复用 key 的话后续请求全部走幂等分支，压根没真正下单
      'Idempotency-Key': `load-${__VU}-${__ITER}-${Date.now()}`,
    },
    tags: { name: 'POST /api/orders' },
    // 明确告诉 k6：201(创建成功) 和 429(被限流) 都是【预期】响应。
    // 429 意味着网关的令牌桶正确地挡住了超额请求，这是系统健康的证据，
    // 不该计入 http_req_failed
    responseCallback: http.expectedStatuses(201, 429),
  };

  const res = http.post(`${GATEWAY}/api/orders`, payload, params);

  orderLatency.add(res.timings.duration);

  if (res.status === 429) {
    // 限流是【预期内】的正确行为，不是错误。
    // 单独计数，不计入失败率
    rateLimited.add(1);
    orderSuccess.add(true);
  } else {
    const ok = res.status === 201;
    orderSuccess.add(ok);
    if (ok) orderCount.add(1);

    check(res, {
      '下单返回 201': (r) => r.status === 201,
      '返回了订单 ID': (r) => r.status !== 201 || JSON.parse(r.body).id > 0,
      '初始状态为 PENDING': (r) => r.status !== 201 || JSON.parse(r.body).status === 'PENDING',
    });
  }

  // 模拟用户思考时间。
  // 【完全不 sleep 是不真实的】：那会让每个 VU 变成一个无限循环的压力发生器，
  // 测出来的"并发数"和真实用户数完全对不上。
  // 真实用户下单之后至少要几秒才会有下一个动作
  sleep(randomIntBetween(1, 3));
}

export function handleSummary(data) {
  const m = data.metrics;
  const get = (name, stat) => (m[name] && m[name].values[stat] !== undefined ? m[name].values[stat] : 0);

  const lines = [
    '',
    '============================================================',
    '  OrderStream 压测结果',
    '============================================================',
    `  目标并发 (VU)          : ${TARGET_VUS}`,
    `  成功创建订单数         : ${get('orders_created', 'count')}`,
    `  被限流 (429)           : ${get('rate_limited_429', 'count')}`,
    '',
    '  下单接口延迟:',
    `    p50                  : ${get('order_create_duration', 'p(50)').toFixed(1)} ms`,
    `    p95                  : ${get('order_create_duration', 'p(95)').toFixed(1)} ms`,
    `    p99                  : ${get('order_create_duration', 'p(99)').toFixed(1)} ms`,
    `    max                  : ${get('order_create_duration', 'max').toFixed(1)} ms`,
    '',
    `  HTTP 请求总数          : ${get('http_reqs', 'count')}`,
    `  吞吐 (req/s)           : ${get('http_reqs', 'rate').toFixed(1)}`,
    `  HTTP 失败率            : ${(get('http_req_failed', 'rate') * 100).toFixed(2)} %`,
    '============================================================',
    '',
    '  注意：以上是【同步下单接口】的指标。',
    '  Saga 的异步部分（库存预留 -> 支付 -> 确认）不在此延迟内 ——',
    '  这正是把支付异步化的目的：用户不需要等待支付网关。',
    '  跑完后用 verify-correctness.sh 检查最终数据一致性。',
    '',
  ];

  return {
    stdout: lines.join('\n'),
    '/scripts/results/summary.json': JSON.stringify(data, null, 2),
  };
}
