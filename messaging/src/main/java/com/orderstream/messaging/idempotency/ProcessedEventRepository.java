package com.orderstream.messaging.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    /**
     * 查重。走的是复合主键 (event_id, consumer) 的索引，是一次极快的主键查找。
     *
     * 注意不能只按 eventId 查 —— 见 ProcessedEvent.consumer 字段的注释，
     * 那会导致第二个消费者组静默失效。
     */
    boolean existsByEventIdAndConsumer(String eventId, String consumer);

    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}
