package com.orderstream.order.entity;

import com.orderstream.common.web.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static com.orderstream.order.entity.Order.OrderStatus.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 订单状态机测试。
 *
 * ============================================================================
 * 【为什么这是整个项目最值得写的一组测试】
 * ============================================================================
 *
 * 状态机封装了订单最核心的业务规则。它出错的后果是【数据损坏】——
 * 已送达的订单被取消、已取消的订单被重新激活、订单状态倒退。
 * 这类 bug 不会让程序崩溃，只会静默地产出错误数据，
 * 往往要等到财务对账时才被发现。
 *
 * 而且这组测试【不需要任何 mock、不需要数据库、不需要 Spring 容器】，
 * 毫秒级跑完。这正是把业务规则放进实体（充血模型）的直接收益：
 * 规则可以被独立地、廉价地验证。
 *
 * 如果规则散落在 Service 里（贫血模型），测它就需要 mock 掉
 * repository、outbox、SecurityContext……测试又慢又脆，
 * 最后大家就干脆不写了。
 * 【可测试性不是测试的问题，是设计的问题】。
 */
class OrderStateMachineTest {

    private Order newOrder(Order.OrderStatus initial) {
        return Order.builder()
                .id(1L)
                .userId(42L)
                .restaurantId(7L)
                .status(initial)
                .totalAmount(new BigDecimal("29.97"))
                .build();
    }

    @Nested
    @DisplayName("合法的状态流转")
    class ValidTransitions {

        @Test
        @DisplayName("完整正常流程：PENDING -> CONFIRMED -> PREPARING -> DELIVERED")
        void happyPath() {
            Order order = newOrder(PENDING);

            order.transitionTo(CONFIRMED, null);
            assertThat(order.getStatus()).isEqualTo(CONFIRMED);

            order.transitionTo(PREPARING, null);
            assertThat(order.getStatus()).isEqualTo(PREPARING);

            order.transitionTo(DELIVERED, null);
            assertThat(order.getStatus()).isEqualTo(DELIVERED);
        }

        @ParameterizedTest(name = "{0} 状态可以被取消")
        @EnumSource(value = Order.OrderStatus.class, names = {"PENDING", "CONFIRMED", "PREPARING"})
        @DisplayName("未到终态的订单都可以取消")
        void canCancelFromNonTerminalStates(Order.OrderStatus from) {
            Order order = newOrder(from);

            order.transitionTo(CANCELLED, "用户主动取消");

            assertThat(order.getStatus()).isEqualTo(CANCELLED);
            // 取消必须记录原因：用户最想知道的就是"为什么"，
            // 只说"已取消"是最容易招致投诉的
            assertThat(order.getCancelReason()).isEqualTo("用户主动取消");
        }
    }

    @Nested
    @DisplayName("非法流转必须被拒绝")
    class InvalidTransitions {

        @Test
        @DisplayName("不能跳过中间状态：PENDING 不能直接到 DELIVERED")
        void cannotSkipStates() {
            Order order = newOrder(PENDING);

            assertThatThrownBy(() -> order.transitionTo(DELIVERED, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("非法的订单状态流转");

            // 抛异常后状态必须保持不变 —— 不能出现"抛了异常但状态已经被改了一半"
            assertThat(order.getStatus()).isEqualTo(PENDING);
        }

        @Test
        @DisplayName("状态不能倒退：CONFIRMED 不能回到 PENDING")
        void cannotGoBackwards() {
            Order order = newOrder(CONFIRMED);

            assertThatThrownBy(() -> order.transitionTo(PENDING, null))
                    .isInstanceOf(BusinessException.class);
        }

        /**
         * 【这个测试直接对应原版代码里的一个真实 bug】
         *
         * 原版的校验逻辑第一行是：
         *     if (to == CANCELLED) return;      // 任何状态都能取消
         *
         * 意味着【已送达的订单可以被取消】。业务上这是错的：
         * 餐已经吃了，"取消"意味着要退款，但食物退不回来。
         * 这种情况必须走售后/退款流程，而不是简单地把状态改成 CANCELLED。
         *
         * 终态就该是终态，这是状态机最基本的约束。
         */
        @Test
        @DisplayName("已送达的订单不能被取消（原版的真实 bug）")
        void cannotCancelDeliveredOrder() {
            Order order = newOrder(DELIVERED);

            assertThatThrownBy(() -> order.transitionTo(CANCELLED, "想退货"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("DELIVERED -> CANCELLED");
        }

        @Test
        @DisplayName("已取消的订单不能被重新激活")
        void cannotReviveCancelledOrder() {
            Order order = newOrder(CANCELLED);

            assertThatThrownBy(() -> order.transitionTo(CONFIRMED, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("幂等性：重复的状态变更请求")
    class Idempotency {

        /**
         * 【这个行为是分布式系统的刚需，不是偷懒】
         *
         * Kafka 是 at-least-once，重复投递必然发生。
         * 一条已经处理过的 PaymentResultEvent 重投时，
         * 订单已经是 CONFIRMED 了，编排器会再次要求转到 CONFIRMED。
         *
         * 如果这里抛异常：Kafka 重试 -> 耗尽 -> 进死信队列 -> 触发告警
         * -> 有人半夜被叫醒，查半天发现是个完全正常的重复消息。
         *
         * 所以"转到当前状态"必须被当作"无事发生"而安全返回。
         */
        @Test
        @DisplayName("转到当前状态是无操作，不抛异常")
        void transitionToSameStateIsNoOp() {
            Order order = newOrder(CONFIRMED);

            assertThatNoException().isThrownBy(() -> order.transitionTo(CONFIRMED, null));
            assertThat(order.getStatus()).isEqualTo(CONFIRMED);
        }

        @Test
        @DisplayName("终态也接受重复的同状态请求")
        void terminalStateAcceptsSameState() {
            Order order = newOrder(CANCELLED);
            assertThatNoException().isThrownBy(() -> order.transitionTo(CANCELLED, "再次取消"));
        }
    }

    @Nested
    @DisplayName("金额的不变量")
    class TotalAmountInvariants {

        @Test
        @DisplayName("金额不能为负")
        void rejectsNegativeAmount() {
            Order order = newOrder(PENDING);

            assertThatThrownBy(() -> order.applyTotal(new BigDecimal("-0.01")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("负数");
        }

        @Test
        @DisplayName("金额不能为 null")
        void rejectsNullAmount() {
            Order order = newOrder(PENDING);
            assertThatThrownBy(() -> order.applyTotal(null))
                    .isInstanceOf(BusinessException.class);
        }

        /**
         * 订单一旦进入 Saga（离开 PENDING），金额就被锁定了。
         *
         * 这是个关键的不变量：支付金额已经发给 payment-service 了，
         * 此时再改订单金额，账就对不上了 —— 用户付了 A，订单显示 B。
         */
        @Test
        @DisplayName("订单进入处理流程后金额不可再改")
        void cannotChangeAmountAfterConfirmed() {
            Order order = newOrder(CONFIRMED);

            assertThatThrownBy(() -> order.applyTotal(new BigDecimal("1.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不可再修改");
        }

        @Test
        @DisplayName("PENDING 状态下可以设置金额")
        void canSetAmountWhilePending() {
            Order order = newOrder(PENDING);
            order.applyTotal(new BigDecimal("59.99"));
            assertThat(order.getTotalAmount()).isEqualByComparingTo("59.99");
        }
    }

    @Nested
    @DisplayName("对象级授权（BOLA 防护）")
    class OwnershipChecks {

        /**
         * 【这组测试守护的是原版最严重的安全漏洞】
         *
         * 原版的 getOrderById 完全没有归属校验，
         * 任何登录用户遍历 /api/orders/{1..10000} 就能读走全站订单。
         * 这是 OWASP API Security Top 10 的第一名（BOLA / IDOR）。
         *
         * 把判断放在实体上并为它写测试，意味着这个规则不会在
         * 将来的重构中被悄悄弄丢 —— 一旦有人改坏，测试立刻变红。
         */
        @Test
        @DisplayName("订单属于创建它的用户")
        void ownedByCreator() {
            Order order = newOrder(PENDING);
            assertThat(order.isOwnedBy(42L)).isTrue();
        }

        @Test
        @DisplayName("订单不属于其他用户")
        void notOwnedByOthers() {
            Order order = newOrder(PENDING);
            assertThat(order.isOwnedBy(43L)).isFalse();
        }
    }

    @Nested
    @DisplayName("终态判定")
    class TerminalStates {

        @ParameterizedTest(name = "{0} 是终态")
        @EnumSource(value = Order.OrderStatus.class, names = {"DELIVERED", "CANCELLED"})
        void terminalStates(Order.OrderStatus status) {
            assertThat(newOrder(status).isTerminal()).isTrue();
        }

        @ParameterizedTest(name = "{0} 不是终态")
        @EnumSource(value = Order.OrderStatus.class, names = {"PENDING", "CONFIRMED", "PREPARING"})
        void nonTerminalStates(Order.OrderStatus status) {
            assertThat(newOrder(status).isTerminal()).isFalse();
        }

        @Test
        @DisplayName("只有 PENDING 表示 Saga 进行中")
        void onlyPendingIsInFlight() {
            assertThat(newOrder(PENDING).isInFlight()).isTrue();
            assertThat(newOrder(CONFIRMED).isInFlight()).isFalse();
        }
    }
}
