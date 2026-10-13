package com.orderstream.order.repository;

import com.orderstream.order.saga.OrderSaga;
import com.orderstream.order.saga.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, String> {

    Optional<OrderSaga> findByOrderId(Long orderId);

    /**
     * 找出卡住的 Saga：还在进行中，但很久没更新了。
     *
     * 走 idx_sagas_in_flight 部分索引
     *   (updated_at) WHERE state IN ('STOCK_PENDING','PAYMENT_PENDING')
     *
     * 部分索引在这里特别有价值：已完成的 Saga 会累积到几千万条，
     * 但"进行中"的永远只有几十条。索引里只有这几十行，
     * 每分钟扫一次的成本几乎为零。
     * 如果用全列索引，每次扫描都要过滤几千万条无关记录。
     */
    List<OrderSaga> findByStateInAndUpdatedAtBefore(Collection<SagaState> states, Instant cutoff);

    /**
     * 带上限的卡死 Saga 查询。
     *
     * 【LIMIT 不是可选的优化，是必需的保护】
     *   没有 LIMIT 时，一次故障积压的几万条记录会被一次性载入内存并处理，
     *   单次执行几分钟。而 Spring 的 @Scheduled 默认只有【1 个线程】，
     *   这几分钟里同一服务的其他定时任务（比如 OutboxRelay）
     *   一次都不会被调度 —— 我实际踩到过这个坑，
     *   表现是"Outbox 完全停止工作"，排查方向完全被带偏。
     *
     * 用原生 SQL 是因为 Spring Data 的派生查询不支持 LIMIT
     * （只能用 Pageable，但那会多一次 count 查询，在大表上很贵）。
     */
    @Query(value = """
            SELECT * FROM order_sagas
            WHERE state = ANY(CAST(:states AS varchar[]))
              AND updated_at < :cutoff
            ORDER BY updated_at
            LIMIT :limit
            """, nativeQuery = true)
    List<OrderSaga> findStuck(@Param("states") List<String> states,
                              @Param("cutoff") Instant cutoff,
                              @Param("limit") int limit);

    /** 监控用：各状态的 Saga 数量。COMPENSATED 或 FAILED 突增说明下游出问题了 */
    long countByState(SagaState state);
}
