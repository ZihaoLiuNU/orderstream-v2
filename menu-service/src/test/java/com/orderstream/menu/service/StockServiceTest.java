package com.orderstream.menu.service;

import com.orderstream.common.Topics;
import com.orderstream.common.event.*;
import com.orderstream.menu.entity.MenuItem;
import com.orderstream.menu.entity.StockReservation;
import com.orderstream.menu.entity.StockReservationItem;
import com.orderstream.menu.repository.MenuItemRepository;
import com.orderstream.menu.repository.StockReservationRepository;
import com.orderstream.messaging.outbox.OutboxPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 库存预留 / 释放的测试。
 *
 * 重点验证三件事：
 *   1. 库存不足时，【已经扣掉的那部分必须被归还】——
 *      这是最容易写漏的地方，漏了就是库存凭空消失
 *   2. 失败也要回报结果 —— 漏发回执会让编排器永远等下去，订单卡死
 *   3. 补偿的幂等性 —— 重复的释放指令不能把库存多加一份
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockServiceTest {

    @Mock private MenuItemRepository menuItemRepository;
    @Mock private StockReservationRepository reservationRepository;
    @Mock private OutboxPublisher outboxPublisher;

    @InjectMocks private StockService stockService;

    private static final String SAGA_ID = "saga-abc";
    private static final Long ORDER_ID = 100L;

    private MenuItem menuItem(Long id, String name, int stock) {
        return MenuItem.builder()
                .id(id).restaurantId(1L).name(name)
                .price(new BigDecimal("9.99"))
                .stockQuantity(stock).available(true)
                .build();
    }

    private OrderLineItem line(Long menuItemId, int qty) {
        return OrderLineItem.builder()
                .menuItemId(menuItemId).menuItemName("菜品" + menuItemId)
                .quantity(qty).unitPrice(new BigDecimal("9.99"))
                .build();
    }

    private StockReservationRequestedEvent request(OrderLineItem... items) {
        return StockReservationRequestedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .orderId(ORDER_ID).sagaId(SAGA_ID)
                .items(List.of(items))
                .build();
    }

    // ======================================================================
    @Nested
    @DisplayName("库存预留")
    class Reserve {

        @Test
        @DisplayName("库存充足 -> 全部扣减成功，写预留单，回报成功")
        void successfulReservation() {
            when(menuItemRepository.findByIdIn(any()))
                    .thenReturn(List.of(menuItem(1L, "Burger", 100), menuItem(2L, "Fries", 50)));
            // 返回 1 表示条件 UPDATE 命中了一行，即扣减成功
            when(menuItemRepository.tryDeductStock(anyLong(), anyInt())).thenReturn(1);

            stockService.reserve(request(line(1L, 2), line(2L, 1)));

            // 两个菜品各扣一次
            verify(menuItemRepository).tryDeductStock(1L, 2);
            verify(menuItemRepository).tryDeductStock(2L, 1);

            // 写了预留单，状态是 RESERVED
            ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(StockReservation.Status.RESERVED);
            assertThat(captor.getValue().getItems()).hasSize(2);

            // 回报成功
            ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.STOCK_RESERVATION_RESULT), eq("100"), evt.capture());
            assertThat(((StockReservationResultEvent) evt.getValue()).isSuccess()).isTrue();
        }

        /**
         * 【这是整个 StockService 最关键的一个测试】
         *
         * 订单里有 3 个菜品，前 2 个扣减成功，第 3 个库存不足。
         *
         * 如果不归还前 2 个已扣的库存，后果是：
         *   那 2 份库存【凭空消失】了 —— 系统里显示卖掉了，
         *   但没有对应的订单、没有收入、也没有任何人知道。
         *   这种"静默的库存泄漏"会一点点累积，
         *   直到有一天盘点时发现账实不符，而那时已经无从追溯了。
         *
         * 这也是"部分失败"这个分布式系统核心难题的一个微缩版：
         *   一个逻辑操作由多个子操作组成，中间失败时必须把前面的都撤销。
         */
        @Test
        @DisplayName("库存不足 -> 归还已扣减的部分，不写预留单，回报失败")
        void insufficientStockRollsBackPartialDeductions() {
            when(menuItemRepository.findByIdIn(any())).thenReturn(List.of(
                    menuItem(1L, "Burger", 100),
                    menuItem(2L, "Fries", 100),
                    menuItem(3L, "Coke", 1)));

            // 前两个成功(1)，第三个库存不足(0)
            when(menuItemRepository.tryDeductStock(1L, 2)).thenReturn(1);
            when(menuItemRepository.tryDeductStock(2L, 1)).thenReturn(1);
            when(menuItemRepository.tryDeductStock(3L, 5)).thenReturn(0);

            stockService.reserve(request(line(1L, 2), line(2L, 1), line(3L, 5)));

            // 【核心断言】前两个必须被归还，数量要精确对应
            verify(menuItemRepository).restoreStock(1L, 2);
            verify(menuItemRepository).restoreStock(2L, 1);
            // 第三个根本没扣成功，不能归还 —— 归还会凭空多出 5 份
            verify(menuItemRepository, never()).restoreStock(eq(3L), anyInt());

            // 不能写预留单：这次预留整体失败了
            verify(reservationRepository, never()).save(any());

            // 【必须回报失败】—— 漏发的话编排器会永远等下去，订单永久卡在 PENDING
            ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.STOCK_RESERVATION_RESULT), eq("100"), evt.capture());

            StockReservationResultEvent result = (StockReservationResultEvent) evt.getValue();
            assertThat(result.isSuccess()).isFalse();
            // 失败原因要可读，它会一路传给用户
            assertThat(result.getFailureReason()).contains("Coke").contains("库存不足");
        }

        @Test
        @DisplayName("菜品不存在 -> 回报失败，不做任何扣减")
        void unknownMenuItemFails() {
            when(menuItemRepository.findByIdIn(any())).thenReturn(List.of());

            stockService.reserve(request(line(999L, 1)));

            verify(menuItemRepository, never()).tryDeductStock(anyLong(), anyInt());
            ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
            verify(outboxPublisher).publish(eq(Topics.STOCK_RESERVATION_RESULT), any(), evt.capture());
            assertThat(((StockReservationResultEvent) evt.getValue()).isSuccess()).isFalse();
        }

        /**
         * 【防死锁：加锁顺序必须一致】
         *
         * 每次 UPDATE 都会对那一行加排他锁，持有到事务提交。
         * 如果不排序：
         *   事务A 处理订单[1, 2]，先锁 1 再锁 2
         *   事务B 处理订单[2, 1]，先锁 2 再锁 1
         *   -> A 持 1 等 2，B 持 2 等 1 -> 死锁
         *
         * Postgres 会检测到并强制中止一个事务，表现为随机的、
         * 无法复现的下单失败（报 deadlock detected）。
         *
         * 统一按 ID 升序加锁后，环形等待在结构上不可能形成。
         * 这是操作系统课本里"破坏循环等待条件"的直接应用。
         */
        @Test
        @DisplayName("扣减顺序必须按菜品 ID 升序（防死锁）")
        void deductsInAscendingIdOrder() {
            when(menuItemRepository.findByIdIn(any())).thenReturn(List.of(
                    menuItem(3L, "C", 100), menuItem(1L, "A", 100), menuItem(2L, "B", 100)));
            when(menuItemRepository.tryDeductStock(anyLong(), anyInt())).thenReturn(1);

            // 故意乱序传入
            stockService.reserve(request(line(3L, 1), line(1L, 1), line(2L, 1)));

            // 必须按 1 -> 2 -> 3 的顺序执行
            var inOrder = inOrder(menuItemRepository);
            inOrder.verify(menuItemRepository).tryDeductStock(1L, 1);
            inOrder.verify(menuItemRepository).tryDeductStock(2L, 1);
            inOrder.verify(menuItemRepository).tryDeductStock(3L, 1);
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("库存释放（补偿事务）")
    class Release {

        private StockReleaseRequestedEvent releaseEvent() {
            return StockReleaseRequestedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .occurredAt(Instant.now())
                    .orderId(ORDER_ID).sagaId(SAGA_ID)
                    .build();
        }

        private StockReservation reservation(StockReservation.Status status) {
            StockReservation r = StockReservation.builder()
                    .sagaId(SAGA_ID).orderId(ORDER_ID).status(status)
                    .items(List.of(
                            StockReservationItem.builder().sagaId(SAGA_ID).menuItemId(1L).quantity(2).build(),
                            StockReservationItem.builder().sagaId(SAGA_ID).menuItemId(2L).quantity(1).build()))
                    .build();
            return r;
        }

        @Test
        @DisplayName("释放已预留的库存 -> 全部归还，状态改为 RELEASED")
        void releasesReservedStock() {
            when(reservationRepository.findWithItemsBySagaId(SAGA_ID))
                    .thenReturn(Optional.of(reservation(StockReservation.Status.RESERVED)));

            stockService.release(releaseEvent());

            verify(menuItemRepository).restoreStock(1L, 2);
            verify(menuItemRepository).restoreStock(2L, 1);

            ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservationRepository).save(captor.capture());
            // 【语义补偿而非回滚】：记录保留下来并标记为 RELEASED，而不是删除。
            // "这批库存曾经被占用过"是审计事实，不能抹掉
            assertThat(captor.getValue().getStatus()).isEqualTo(StockReservation.Status.RELEASED);
        }

        /**
         * 【补偿必须幂等】
         *
         * Kafka 是 at-least-once，补偿消息同样会重复投递。
         * 如果重复执行，库存会被【多加一份】—— 凭空变出商品，
         * 然后超卖，然后有订单做不出来。
         *
         * 这里靠 status 状态机实现幂等，不需要额外的去重表：
         * 已经是 RELEASED 的预留单直接返回。
         */
        @Test
        @DisplayName("重复的释放请求 -> 必须被忽略，不能重复归还")
        void duplicateReleaseIsIgnored() {
            when(reservationRepository.findWithItemsBySagaId(SAGA_ID))
                    .thenReturn(Optional.of(reservation(StockReservation.Status.RELEASED)));

            stockService.release(releaseEvent());

            verify(menuItemRepository, never()).restoreStock(anyLong(), anyInt());
            verify(reservationRepository, never()).save(any());
        }

        /**
         * 【补偿必须能处理"要补偿的事根本没发生过"】
         *
         * 场景：预留步骤本身就失败了（库存不足），
         * 但编排器仍然统一发出了释放指令
         * （编排器不精确记录每一步的成败，统一发补偿更简单也更健壮）。
         *
         * 如果这里抛异常：Kafka 重试 -> 耗尽 -> 进死信队列 -> 触发告警
         * -> 值班的人被一个完全正常的流程叫醒。
         */
        @Test
        @DisplayName("预留单不存在 -> 安全返回，不抛异常")
        void missingReservationIsNotAnError() {
            when(reservationRepository.findWithItemsBySagaId(SAGA_ID)).thenReturn(Optional.empty());

            stockService.release(releaseEvent());   // 不应抛异常

            verify(menuItemRepository, never()).restoreStock(anyLong(), anyInt());
        }
    }
}
