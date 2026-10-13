package com.orderstream.payment.controller;

import com.orderstream.common.security.CurrentUser;
import com.orderstream.common.web.ForbiddenException;
import com.orderstream.common.web.ResourceNotFoundException;
import com.orderstream.payment.dto.PaymentResponse;
import com.orderstream.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 支付记录查询接口。【只读】。
 *
 * ============================================================================
 * 【这里没有"发起支付"的接口，这是刻意的安全设计】
 * ============================================================================
 *
 *   支付只能由 Saga 通过 Kafka 事件发起。如果开放一个 POST /api/payments，
 *   那么任何能访问内网的人都可以绕过订单流程直接扣款或退款。
 *
 *   把"发起支付"这个能力限定在事件驱动的路径上，
 *   就保证了每一笔支付都必然是某个合法 Saga 的一部分，
 *   都有对应的订单、有完整的上下文、有补偿路径。
 *
 *   通用原则：能力越危险，入口就应该越窄。
 *   不需要的接口就不要暴露 —— 不存在的接口是最安全的接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentRepository paymentRepository;

    /**
     * 我的支付记录。
     *
     * 【注意这里没有"按支付 ID 查单条"的接口】
     *   按 userId 过滤天然就杜绝了 BOLA —— 查询条件里就带了"只能是我的"，
     *   结构上不存在"查到别人的"这种可能。
     *
     *   这是防 BOLA 更优雅的思路：
     *     与其"查出来再判断是不是你的"（需要每个调用方都记得判断），
     *     不如【让查询本身就只能查到你的】（结构上不可能出错）。
     *
     *   能用查询条件表达的授权，就不要用事后判断来实现。
     */
    @GetMapping("/my")
    @PreAuthorize("hasRole('USER')")
    @Transactional(readOnly = true)
    public Page<PaymentResponse> myPayments(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        CurrentUser user = CurrentUser.get();
        return paymentRepository.findByUserId(user.getUserId(), pageable)
                .map(PaymentResponse::from);
    }

    /**
     * 按订单查支付记录。用户查自己的，管理员查任意的。
     *
     * 这个接口【必须】显式做归属校验，因为查询条件是 orderId 而不是 userId ——
     * 单凭 orderId 查出来的记录可能属于任何人。
     * 对比上面的 /my，这里就是那种"必须事后判断"的情况。
     */
    @GetMapping("/by-order/{orderId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Transactional(readOnly = true)
    public PaymentResponse byOrder(@PathVariable Long orderId) {

        var payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("该订单没有支付记录: " + orderId));

        CurrentUser user = CurrentUser.get();
        if (!user.isAdmin() && !payment.getUserId().equals(user.getUserId())) {
            // warn 级别 + 结构化字段：越权尝试是安全信号，应该能被告警规则捞出来
            log.warn("[安全] 越权访问支付记录被拒绝 userId={} orderId={}", user.getUserId(), orderId);
            throw new ForbiddenException("无权查看该订单的支付记录");
        }
        return PaymentResponse.from(payment);
    }
}
