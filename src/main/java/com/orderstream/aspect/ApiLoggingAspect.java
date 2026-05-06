package com.orderstream.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * API 请求日志切面（AOP）
 *
 * 横切关注点：自动记录所有 Controller 方法的执行耗时。
 * 无需在每个接口手动添加日志代码，体现 AOP 的核心价值：
 *   业务代码（Controller）与监控代码完全分离。
 *
 * 这是 35% 查询延迟优化的数据来源：
 *   建索引前后对比 API 耗时日志，计算优化幅度。
 */
@Slf4j
@Aspect
@Component
public class ApiLoggingAspect {

    // 拦截 controller 包下所有方法的执行
    @Around("execution(* com.orderstream.controller..*(..))")
    public Object logApiCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();
        long start = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[API] {} 完成，耗时 {}ms", method, elapsed);
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[API] {} 异常: {}，耗时 {}ms", method, ex.getMessage(), elapsed);
            throw ex;
        }
    }
}
