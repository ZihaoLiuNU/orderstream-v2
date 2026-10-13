package com.orderstream.menu.repository;

import com.orderstream.menu.entity.StockReservation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository extends JpaRepository<StockReservation, String> {

    /**
     * 查预留单并【一次性】把明细一起加载进来。
     *
     * @EntityGraph 让 Hibernate 生成一条带 LEFT JOIN FETCH 的 SQL，
     * 而不是先查主表、再为每条明细发一次查询（N+1）。
     *
     * 释放库存时必须遍历所有明细，如果不用 EntityGraph，
     * 一个 10 个菜品的订单就会产生 1 + 1 = 2 次查询（这里明细是一个集合，
     * 所以是 1 次主查询 + 1 次集合加载，不算太糟）；
     * 但如果在循环里处理多个预留单，就会退化成真正的 N+1。
     * 显式声明抓取策略，行为才可预测。
     */
    @EntityGraph(attributePaths = "items")
    Optional<StockReservation> findWithItemsBySagaId(String sagaId);

    /**
     * 找出卡住的预留：状态还是 RESERVED，但已经超过阈值时间没更新。
     *
     * 这是【Saga 超时补偿】的数据来源。
     * 为什么需要它：Saga 的某一步可能因为消息丢失、服务长时间宕机等原因
     * 永远等不到回执，库存就被永久占用了 —— 这叫"库存泄漏"。
     * 定时扫描 + 自动释放是唯一的兜底。
     *
     * 这条查询走 idx_stock_reservations_stale 部分索引
     * (updated_at) WHERE status = 'RESERVED'，
     * 索引里只有当前活跃的预留，通常只有几百行，扫描极快。
     */
    List<StockReservation> findByStatusAndUpdatedAtBefore(StockReservation.Status status, Instant cutoff);
}
