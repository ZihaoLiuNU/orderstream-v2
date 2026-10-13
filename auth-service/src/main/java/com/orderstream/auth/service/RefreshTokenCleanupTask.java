package com.orderstream.auth.service;

import com.orderstream.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 定期清理过期的 refresh token 记录。
 *
 * 为什么需要：refresh_tokens 表是只增不减的。7 天 TTL + 每天刷新几次，
 * 一年下来是百万级的死数据，索引膨胀、VACUUM 变慢、备份变大。
 * 任何带 TTL 的表都必须配一个清理任务，这是设计时就要想到的，不是上线后再补。
 *
 * 【多实例下的问题 —— 必须主动讲】
 *   @Scheduled 是【每个实例各跑各的】。auth-service 扩到 3 个实例，
 *   这个任务就会在同一时刻被执行 3 次。
 *
 *   对「删除过期数据」这种幂等操作，重复执行只是浪费，不会出错。
 *   但如果任务是「给每个用户发一封账单邮件」，重复执行就是发三封 —— 生产事故。
 *
 *   正确做法是分布式锁：
 *     - ShedLock（最轻量，用数据库或 Redis 做锁，加个注解即可）
 *     - 或把定时任务从应用里拆出去，交给 K8s CronJob / AWS EventBridge 调度
 *   我这里保留 @Scheduled 是因为操作幂等且开销极小，
 *   但知道边界在哪里，比盲目加锁更重要。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupTask {

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * fixedDelay 而不是 fixedRate：
     *   fixedRate 是「每隔 1 小时启动一次」，如果上一次还没跑完就会重叠执行。
     *   fixedDelay 是「上一次结束后再等 1 小时」，保证同一实例内不会重叠。
     *   清理任务在数据量大时可能跑很久，必须用 fixedDelay。
     *
     * initialDelay 错开启动：应用刚启动时在忙着预热连接池、建立 Kafka 连接，
     * 这时候再来一个全表扫描的删除任务是火上浇油。
     */
    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 60 * 60 * 1000)
    @Transactional
    public void purgeExpiredTokens() {
        // 多保留 30 天再删：过期的 token 记录还有审计价值
        // （比如事后调查「这个账号在什么时候、用什么设备刷新过」）。
        // 直接删掉过期记录等于销毁审计线索
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("[清理] 删除 {} 条过期超过 30 天的 refresh token", deleted);
        }
    }
}
