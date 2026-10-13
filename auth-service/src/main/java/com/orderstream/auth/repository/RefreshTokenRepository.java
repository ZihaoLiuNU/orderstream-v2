package com.orderstream.auth.repository;

import com.orderstream.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 吊销一整个 token family。
     *
     * @Modifying 告诉 Spring Data 这是写操作（默认假设 @Query 是查询）。
     * clearAutomatically=true 很关键：批量 UPDATE 是直接发给数据库的，
     * 绕过了 Hibernate 的一级缓存(持久化上下文)。如果不清缓存，
     * 同一事务里后续读到的还是内存里的旧对象 —— 一个非常隐蔽的数据不一致来源。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
