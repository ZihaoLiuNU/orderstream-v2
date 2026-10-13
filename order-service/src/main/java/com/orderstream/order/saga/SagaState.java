package com.orderstream.order.saga;

/**
 * Saga 状态机。
 *
 * 完整流程（3 步 + 2 个补偿）：
 *
 *   下单
 *    │
 *    ├─> STOCK_PENDING ──(预留成功)──> PAYMENT_PENDING ──(支付成功)──> COMPLETED
 *    │         │                              │
 *    │    (预留失败)                      (支付失败)
 *    │         │                              │
 *    │         v                              v
 *    │    COMPENSATED <──(释放库存补偿)── COMPENSATED
 *    │     订单 CANCELLED                 订单 CANCELLED
 *
 * 【Saga 步骤顺序的设计原则 】
 *
 *   为什么是"先扣库存再付款"而不是反过来？
 *
 *   把【容易补偿】的步骤放前面，【难补偿】的放后面。
 *     释放库存：本地的一条 UPDATE，瞬间完成，几乎不可能失败
 *     退款：要调第三方支付网关，慢、可能失败、有手续费、可能要 T+1
 *
 *   先付款后扣库存的话，库存不足时就必须退款 —— 用高代价的补偿去
 *   处理一个本来可以用低代价避免的失败。
 *
 *   最难补偿的那一步叫【pivot transaction（支点事务）】。
 *   Saga 设计的核心就是：pivot 之前的步骤都要可补偿，
 *   pivot 之后的步骤都要"必定成功"（可重试直到成功，retriable）。
 *   在我的流程里，支付是 pivot，它之后只剩下更新本地订单状态，
 *   而那是和记录支付结果在【同一个本地事务】里完成的，不存在独立失败的可能。
 */
public enum SagaState {

    /** 步骤 1 进行中：已发出库存预留请求，等待 menu-service 回执 */
    STOCK_PENDING,

    /** 步骤 2 进行中：库存已预留，已发出支付请求，等待 payment-service 回执 */
    PAYMENT_PENDING,

    /** 全部成功。订单 CONFIRMED，库存实际消耗，款项已收 */
    COMPLETED,

    /**
     * 已执行补偿。订单 CANCELLED。
     *
     * 注意 COMPENSATED 是【终态】，和 COMPLETED 平级 ——
     * Saga 走了补偿路径不代表"失败"，它代表"系统正确地处理了一次业务失败"。
     * 对系统来说，一次因库存不足而取消的订单，和一次成功的订单，
     * 同样是"Saga 正常结束"。真正的失败是下面的 FAILED。
     */
    COMPENSATED,

    /**
     * 不可恢复的失败：补偿本身也失败了，或者出现了状态机无法处理的情况。
     *
     * 这个状态的存在本身就是一种诚实：分布式系统里【一定】存在
     * 自动化手段无法恢复的情况。与其假装不存在，不如显式建模出来，
     * 让它可以被查询、被告警、被人工介入处理。
     *
     * 生产环境里，任何一条 FAILED 记录都应该触发告警并进入人工对账队列。
     */
    FAILED;

    /** Saga 是否还在进行中（兜底扫描器用它筛选） */
    public boolean isInFlight() {
        return this == STOCK_PENDING || this == PAYMENT_PENDING;
    }
}
