package com.orderstream.menu.service;

import com.orderstream.common.event.StockReleaseRequestedEvent;
import com.orderstream.menu.entity.StockReservation;
import com.orderstream.menu.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 超时预留清理器 —— Saga 的兜底安全网。
 *
 * ============================================================================
 * 【它解决的问题：Saga 卡死导致的库存泄漏】
 * ============================================================================
 *
 * 正常流程下，编排器会在支付失败时发出释放指令。但如果：
 *   - 编排器所在的 order-service 在发出释放指令前彻底宕机且数据损坏
 *   - 释放消息因为某种极端情况进了死信队列且没人处理
 *   - 某个人工操作把 Saga 记录删了
 *
 * 那么这批库存会被【永久占用】，没有任何人会来释放它。
 * 表现是：系统显示售罄，但实际上并没有卖出去。这种问题极难发现，
 * 因为没有任何报错，只是销量莫名其妙下降。
 *
 * ============================================================================
 * 【为什么编排器的补偿机制不够，还需要这个】
 * ============================================================================
 *
 * 这是分布式系统设计的一个核心思维方式：
 *   基于消息的协调机制（编排器发补偿指令）依赖"对方还活着并且会来通知我"。
 *   而【基于超时的自愈机制】不依赖任何外部方 —— 我自己就能判断出异常并恢复。
 *
 * 前者是主动路径，后者是兜底路径。健壮的系统两者都要有：
 *   主动路径处理 99.9% 的情况，快速、精确；
 *   兜底路径处理剩下的 0.1%，慢，但【不依赖任何假设】。
 *
 * 这个思路在 TCP 里也有体现：ACK 是主动确认，超时重传是兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleReservationSweeper {

    private final StockReservationRepository reservationRepository;
    private final StockService stockService;

    /**
     * 多久算"卡住"。
     *
     * 这个值必须【显著大于】Saga 的正常完成时间：
     *   正常流程：预留(50ms) + 支付(300ms) + 确认(50ms) + 两次 outbox 轮询(1s) < 2 秒。
     *   设成 10 分钟留了几百倍余量，确保绝不会误杀正在正常进行中的 Saga。
     *
     * 误杀的后果很严重：把一个正在支付的订单的库存释放掉，
     * 支付成功后订单却没有库存了。所以超时阈值宁可保守。
     */
    @Value("${orderstream.stock.stale-reservation-timeout:PT10M}")
    private Duration staleTimeout;

    @Scheduled(fixedDelayString = "${orderstream.stock.sweep-interval:60000}")
    public void sweepStaleReservations() {
        Instant cutoff = Instant.now().minus(staleTimeout);

        List<StockReservation> stale = reservationRepository
                .findByStatusAndUpdatedAtBefore(StockReservation.Status.RESERVED, cutoff);

        if (stale.isEmpty()) {
            return;
        }

        // error 级别：走到这里说明主动补偿路径失效了，这是需要人去查的异常，
        // 不是正常运转的一部分。兜底机制被触发本身就是一个告警信号
        log.error("[库存兜底] 发现 {} 条超过 {} 未完成的预留，Saga 可能已卡死，开始强制释放",
                stale.size(), staleTimeout);

        for (StockReservation reservation : stale) {
            try {
                // 复用正常的释放逻辑，而不是另写一套。
                // 两套逻辑必然会漂移，而且兜底路径平时不执行，漂移了也发现不了
                stockService.release(StockReleaseRequestedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(reservation.getOrderId())
                        .sagaId(reservation.getSagaId())
                        .build());

                log.warn("[库存兜底] 已强制释放 sagaId={} orderId={}",
                        reservation.getSagaId(), reservation.getOrderId());

            } catch (Exception e) {
                // 单条失败不能影响其他条目的清理。
                // 兜底任务尤其要防"一条烂数据卡住整个清理流程"
                log.error("[库存兜底] 释放失败 sagaId={}", reservation.getSagaId(), e);
            }
        }
    }
}
