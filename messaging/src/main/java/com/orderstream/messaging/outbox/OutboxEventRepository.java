package com.orderstream.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 捞取待发送的事件。
     *
     * ========================================================================
     * 【FOR UPDATE SKIP LOCKED —— 这是整个 Outbox 实现里最关键的一行 SQL】
     * ========================================================================
     *
     * 问题：order-service 扩容到 3 个实例后，3 个 relay 会同时轮询同一张 outbox 表。
     *       如果不加锁，三个实例会捞到同一批记录，把同一条消息发 3 次。
     *
     * 朴素解法及其问题：
     *   a) 加分布式锁(Redis/ShedLock)让只有一个实例干活
     *      -> 吞吐被限制在单实例，扩容完全无效，而且锁本身成了新的故障点
     *   b) 普通的 FOR UPDATE（不带 SKIP LOCKED）
     *      -> 实例 2 会【阻塞等待】实例 1 释放锁，变成串行执行，
     *         扩容同样无效，还可能因为锁等待超时而报错
     *
     * SKIP LOCKED 的解法：
     *   「把已经被别人锁住的行直接跳过，去拿下一批没被锁的」。
     *   于是实例 1 锁住 1~100 行，实例 2 自动跳过它们去拿 101~200 行，
     *   实例 3 拿 201~300 行。三个实例【真正并行】，零冲突、零等待、零重复。
     *
     *   这是 Postgres / Oracle / MySQL 8 都支持的能力，
     *   也是用关系数据库实现「可靠任务队列」的标准做法。
     *   很多人一想到队列就上 RabbitMQ，其实中小规模下
     *   一张表 + SKIP LOCKED 更简单也更可靠（少一个要运维的组件）。
     *
     * ORDER BY id：保证按事件产生的顺序投递。
     *   注意这只保证【单个 relay 批次内】有序；多实例并行时全局顺序是不保证的。
     *   但这没关系 —— 真正需要顺序的是「同一个订单的事件」，
     *   而它们靠 Kafka 的分区键(messageKey)保证有序，不依赖 relay 的处理顺序。
     */
    // 注意这里【没有】用 Spring Data 的 @Lock(PESSIMISTIC_WRITE)。
    // @Lock 只对派生查询和 JPQL 生效，对 nativeQuery 无效；
    // 而且 Hibernate 生成的 FOR UPDATE 不支持 SKIP LOCKED 的所有数据库方言组合。
    // 直接把 FOR UPDATE SKIP LOCKED 写进原生 SQL，行为最明确，也最容易 review
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE sent_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnsentBatch(@Param("batchSize") int batchSize);

    /**
     * 清理已成功发送且超过保留期的记录。
     *
     * 为什么不发送成功就立刻删除：
     *   保留一段时间有两个价值 ——
     *   1) 排障：能回答「这条消息到底发出去过没有、什么时候发的」
     *   2) 重放：下游服务出 bug 导致数据错了，可以把 sent_at 置空让 relay 重发
     *      （前提是消费端幂等，所以重放是安全的）
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.sentAt IS NOT NULL AND e.sentAt < :cutoff")
    int deleteSentBefore(@Param("cutoff") Instant cutoff);

    /** 积压监控：待发送数量持续上涨说明 relay 出问题了，应该告警 */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.sentAt IS NULL")
    long countUnsent();
}
