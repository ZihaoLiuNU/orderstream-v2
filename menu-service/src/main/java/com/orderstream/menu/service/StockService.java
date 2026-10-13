package com.orderstream.menu.service;

import com.orderstream.common.Topics;
import com.orderstream.common.event.*;
import com.orderstream.menu.entity.MenuItem;
import com.orderstream.menu.entity.StockReservation;
import com.orderstream.menu.entity.StockReservationItem;
import com.orderstream.menu.repository.MenuItemRepository;
import com.orderstream.menu.repository.StockReservationRepository;
import com.orderstream.messaging.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Saga 步骤 1 的执行者：预留库存 / 释放库存。
 *
 * 这个类是 Saga 参与者(participant)，不是编排者。
 * 它只做两件事：收到指令 -> 执行本地事务 -> 回报结果。
 * 它完全不知道 Saga 的全貌，不知道下一步是支付，也不知道失败后会发生什么。
 * 这种无知是刻意的 —— 参与者一旦开始理解全局流程，编排式就退化成编排式了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final MenuItemRepository menuItemRepository;
    private final StockReservationRepository reservationRepository;
    private final OutboxPublisher outboxPublisher;

    /**
     * 预留库存。
     *
     * 整个方法在一个本地事务里：
     *   扣库存 + 写预留单 + 写 outbox 事件，要么全成功，要么全回滚。
     *   这正是 Saga 每一步的定义 —— 一个【本地】ACID 事务 + 一个补偿动作。
     *
     * 注意这里【没有】任何幂等判断代码。幂等由两层保证：
     *   1) 外层 IdempotentEventProcessor 按 eventId 去重（见 StockEventListener）
     *   2) stock_reservations 的主键是 sagaId，重复插入直接撞主键
     * 两层都在，是因为它们防的是不同的重复：
     *   eventId 防"同一条消息被投递两次"，
     *   sagaId 防"业务上对同一个 Saga 发起了两次预留"。
     */
    @Transactional
    public void reserve(StockReservationRequestedEvent event) {

        // 按 menuItemId 排序后再依次扣减。
        //
        // 【为什么排序 —— 这是防死锁的关键，而且很容易被忽略】
        //   每次 UPDATE 都会对那一行加排他锁，锁一直持有到事务提交。
        //   如果不排序：
        //     事务A 处理订单[菜品1, 菜品2]，先锁 1 再锁 2
        //     事务B 处理订单[菜品2, 菜品1]，先锁 2 再锁 1
        //     -> A 持有1等2，B 持有2等1 -> 死锁
        //   Postgres 会检测到死锁并强制中止其中一个事务（报 deadlock detected），
        //   表现为随机的、无法复现的下单失败。
        //
        //   统一按 ID 升序加锁，所有事务的加锁顺序就一致了，
        //   环形等待在结构上不可能形成。这是操作系统课本里
        //   "破坏死锁四条件之一：循环等待"的直接应用。
        List<OrderLineItem> sortedItems = event.getItems().stream()
                .sorted(Comparator.comparing(OrderLineItem::getMenuItemId))
                .toList();

        // 先把要查的菜品一次性捞出来，用于生成可读的失败原因。
        // 一次 IN 查询，不是循环里逐个查
        Map<Long, MenuItem> itemsById = menuItemRepository
                .findByIdIn(sortedItems.stream().map(OrderLineItem::getMenuItemId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(MenuItem::getId, i -> i));

        List<StockReservationItem> reservedItems = new ArrayList<>();

        for (OrderLineItem line : sortedItems) {
            MenuItem menuItem = itemsById.get(line.getMenuItemId());

            if (menuItem == null) {
                // 菜品不存在是确定性失败，重试也没用，直接回报失败让 Saga 走补偿
                failAndPublish(event, "菜品不存在: id=" + line.getMenuItemId());
                return;
            }

            // 原子条件扣减：返回 1 = 成功，0 = 库存不足。
            // 见 MenuItemRepository.tryDeductStock 里关于为什么不能"先查再改"的长注释
            int updated = menuItemRepository.tryDeductStock(line.getMenuItemId(), line.getQuantity());

            if (updated == 0) {
                /*
                 * 库存不足。
                 *
                 * 【关键：直接 return，不做手工回滚】
                 *   前面已经成功扣减的那些菜品怎么办？
                 *   答案是：什么都不用做 —— 抛异常/正常返回后，
                 *   Spring 会在方法结束时提交或回滚整个事务。
                 *   这里我们要的是【回滚】，所以必须让事务失败。
                 *
                 *   但 failAndPublish 要往 outbox 写失败事件，
                 *   如果整个事务回滚，这条事件也会被回滚掉，编排器就永远等不到回执了。
                 *
                 *   所以这里【不能回滚】，而要正常提交：
                 *   已扣的库存通过下面的手工 restoreStock 归还，
                 *   失败事件正常写入 outbox 并发出去。
                 *   这是 Saga 参与者的一个典型细节 ——
                 *   "业务失败"和"技术失败"要分开处理：
                 *     业务失败(库存不足) -> 事务提交，回报 failure，让 Saga 走补偿
                 *     技术失败(数据库挂了) -> 事务回滚，消息不提交 offset，Kafka 重投
                 */
                reservedItems.forEach(ri ->
                        menuItemRepository.restoreStock(ri.getMenuItemId(), ri.getQuantity()));

                String reason = String.format("菜品「%s」库存不足，当前剩余 %d 份，需要 %d 份",
                        menuItem.getName(), menuItem.getStockQuantity(), line.getQuantity());
                log.info("[库存] 预留失败 sagaId={} 原因={}", event.getSagaId(), reason);
                failAndPublish(event, reason);
                return;
            }

            reservedItems.add(StockReservationItem.builder()
                    .sagaId(event.getSagaId())
                    .menuItemId(line.getMenuItemId())
                    .quantity(line.getQuantity())
                    .build());
        }

        // 全部扣减成功，记录预留单
        StockReservation reservation = StockReservation.builder()
                .sagaId(event.getSagaId())
                .orderId(event.getOrderId())
                .status(StockReservation.Status.RESERVED)
                .items(reservedItems)
                .build();
        reservationRepository.save(reservation);

        // 回报成功。注意走的是 outbox，不是直接发 Kafka ——
        // 这样"库存扣了"和"通知编排器库存扣了"是原子的
        outboxPublisher.publish(
                Topics.STOCK_RESERVATION_RESULT,
                String.valueOf(event.getOrderId()),
                StockReservationResultEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(event.getOrderId())
                        .sagaId(event.getSagaId())
                        .success(true)
                        .build());

        log.info("[库存] 预留成功 sagaId={} orderId={} 共 {} 个菜品",
                event.getSagaId(), event.getOrderId(), reservedItems.size());
    }

    /**
     * 补偿事务：释放已预留的库存。
     *
     * 补偿的三个硬性要求，这个方法逐条满足：
     *   1. 幂等   —— 靠 status 状态机：已 RELEASED 的直接返回
     *   2. 不失败 —— restoreStock 无条件执行，不存在"归还不了"的情况
     *   3. 语义补偿而非回滚 —— 预留记录保留下来并标记为 RELEASED，
     *      而不是删除。"这笔库存曾经被占用过"是审计事实，不能抹掉
     */
    @Transactional
    public void release(StockReleaseRequestedEvent event) {

        Optional<StockReservation> opt = reservationRepository.findWithItemsBySagaId(event.getSagaId());

        if (opt.isEmpty()) {
            /*
             * 找不到预留单。
             *
             * 这【不是】错误，反而是完全正常的：
             *   预留步骤本身就失败了（库存不足），编排器仍然会发出释放指令
             *   （编排器不需要记住每一步到底有没有成功，统一发补偿更简单也更健壮）。
             *
             * 这体现了补偿事务设计的一个重要原则：
             *   补偿必须能处理"要补偿的事情根本没发生过"这种情况。
             *   如果这里抛异常，Kafka 会重试 -> 重试耗尽 -> 进死信队列 -> 误报警。
             */
            log.info("[库存] 无需释放，预留单不存在 sagaId={}（通常是预留本身就失败了）", event.getSagaId());
            return;
        }

        StockReservation reservation = opt.get();

        // 幂等守卫：状态机保证同一个预留只会被释放一次
        if (reservation.getStatus() != StockReservation.Status.RESERVED) {
            log.info("[库存] 重复的释放请求已忽略 sagaId={} 当前状态={}",
                    event.getSagaId(), reservation.getStatus());
            return;
        }

        // 同样按 ID 排序归还，理由和预留时一致：统一加锁顺序，杜绝死锁
        reservation.getItems().stream()
                .sorted(Comparator.comparing(StockReservationItem::getMenuItemId))
                .forEach(item -> menuItemRepository.restoreStock(item.getMenuItemId(), item.getQuantity()));

        reservation.setStatus(StockReservation.Status.RELEASED);
        reservationRepository.save(reservation);

        log.info("[库存] 补偿完成，已释放 sagaId={} orderId={} 共 {} 个菜品",
                event.getSagaId(), reservation.getOrderId(), reservation.getItems().size());
    }

    /**
     * 回报预留失败。
     *
     * 单独抽出来是因为有多个失败分支都要走这段逻辑，
     * 而漏发回执的后果很严重：编排器会永远等下去，订单卡死在 PENDING。
     * 把"必须做的事"收敛到一个方法里，减少漏做的可能。
     */
    private void failAndPublish(StockReservationRequestedEvent event, String reason) {
        outboxPublisher.publish(
                Topics.STOCK_RESERVATION_RESULT,
                String.valueOf(event.getOrderId()),
                StockReservationResultEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(Instant.now())
                        .orderId(event.getOrderId())
                        .sagaId(event.getSagaId())
                        .success(false)
                        .failureReason(reason)
                        .build());
    }
}
