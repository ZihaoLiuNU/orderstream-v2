package com.orderstream.auth.service;

import com.orderstream.auth.dto.*;
import com.orderstream.auth.entity.RefreshToken;
import com.orderstream.auth.entity.User;
import com.orderstream.auth.repository.RefreshTokenRepository;
import com.orderstream.auth.repository.UserRepository;
import com.orderstream.auth.security.JwtIssuer;
import com.orderstream.auth.security.RefreshTokenGenerator;
import com.orderstream.common.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenGenerator refreshTokenGenerator;

    @Value("${jwt.refresh-token-ttl}")
    private Duration refreshTokenTtl;

    /**
     * 一个固定的 BCrypt 哈希，用于用户不存在时的假验证。见 login() 的注释。
     * 值本身无所谓，只要是个合法的 BCrypt 串。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // 先查一次只是为了给出友好错误信息，不是正确性保证。
        // 并发下两个请求可能都通过这个检查，真正的保证是数据库唯一约束（见下面的 catch）
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("用户名已被占用");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("邮箱已被注册");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                // BCrypt 自动生成随机 salt 并嵌进结果里。
                // 所以同一个密码每次哈希结果都不同，这正是彩虹表失效的原因
                .password(passwordEncoder.encode(request.getPassword()))
                // 注册永远只能得到 USER 角色。
                // 绝不能让客户端在请求体里指定 role，那是典型的权限提升漏洞(Mass Assignment)。
                // 注意 RegisterRequest 里根本没有 role 字段，这是有意的设计
                .role(User.Role.USER)
                .build();

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束兜住了并发注册的竞态。翻译成业务异常返回 400，而不是让它变成 500
            throw new BusinessException("用户名或邮箱已被占用");
        }

        log.info("[注册] 新用户 userId={} username={}", user.getId(), user.getUsername());
        return issueTokens(user, UUID.randomUUID());
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------
    @Transactional
    public AuthResponse login(LoginRequest request) {
        var userOpt = userRepository.findByUsername(request.getUsername());

        /*
         * 防用户名枚举。这段代码看起来多余，其实很关键。
         *
         * 朴素写法是：用户不存在就直接 throw。问题在于直接 throw 几乎不耗时，
         * 而用户存在但密码错要跑一次 BCrypt（故意设计成 ~100ms）。
         * 攻击者测量响应时间就能分辨出哪些用户名真实存在，这叫时序侧信道攻击。
         * 拿到有效用户名列表后，就可以针对性地做撞库/爆破。
         *
         * 对策：用户不存在时照样跑一次 BCrypt（拿一个假哈希去比），
         * 让两条路径的耗时特征一致。
         *
         * 另外两条路径返回的错误信息必须完全相同。
         * 把「用户不存在」和「密码错误」分开提示，等于直接把用户是否存在告诉攻击者。
         */
        if (userOpt.isEmpty()) {
            passwordEncoder.matches(request.getPassword(), DUMMY_HASH);
            throw new BusinessException("用户名或密码错误");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // warn 级别：登录失败是安全信号，应该能被告警规则统计出爆破行为
            log.warn("[登录失败] 密码错误 username={}", request.getUsername());
            throw new BusinessException("用户名或密码错误");
        }

        log.info("[登录] userId={} username={}", user.getId(), user.getUsername());
        // 每次登录开启一个新的 token family，代表一台新设备/一个新会话
        return issueTokens(user, UUID.randomUUID());
    }

    // ------------------------------------------------------------------
    // 刷新（轮换 + 重放检测）
    // ------------------------------------------------------------------
    /**
     * 用 refresh token 换一对新 token。
     *
     * Refresh Token Rotation + Reuse Detection，是 OAuth 2.0 安全最佳实践(RFC 9700)
     * 对公开客户端的推荐做法，值得完整讲清楚：
     *
     * 规则 1（轮换）：每次刷新都作废旧 token，发一个新的。
     *   这样每个 refresh token 都是一次性的。
     *
     * 规则 2（重放检测）：如果收到一个已经被用过的 token，
     *   说明它被泄露了，因为合法客户端用完就扔，不会再用第二次。
     *   此时把整个 family 全部吊销，强制用户重新登录。
     *
     * 为什么这套机制有效（关键洞察）：
     *   攻击者偷到 refresh token 后只有两种可能：
     *     a) 攻击者先用，那么合法用户下次刷新时会带着已作废的 token 过来，触发检测
     *     b) 合法用户先用，那么攻击者拿着已作废的 token 来用，触发检测
     *   无论谁先用，第二个人一定会触发重放检测。
     *   所以 token 泄露必然会被发现，最多损失一个刷新周期。
     *   这把「无法察觉的静默泄露」变成了「一定会暴露的入侵」，是质的区别。
     */
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String hash = refreshTokenGenerator.hash(request.getRefreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException("refresh token 无效"));

        // 已被吊销却又被使用，判定为泄露
        if (stored.getRevokedAt() != null) {
            int revoked = refreshTokenRepository.revokeFamily(stored.getFamilyId(), Instant.now());
            // error 级别 + 明确关键词，方便在日志系统里配告警规则
            log.error("[安全告警] 检测到 refresh token 重放 userId={} familyId={}，已吊销该会话全部 {} 个 token",
                    stored.getUserId(), stored.getFamilyId(), revoked);
            throw new BusinessException("会话已失效，请重新登录");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("refresh token 已过期，请重新登录");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BusinessException("用户不存在"));

        // 轮换：作废旧的，发新的（新的仍属于同一个 family）
        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        return issueTokens(user, stored.getFamilyId());
    }

    // ------------------------------------------------------------------
    // 登出
    // ------------------------------------------------------------------
    /**
     * 登出等于吊销整个 family。
     *
     * 这里有个无法回避的真相：
     *   登出只能让 refresh token 立即失效，已经发出去的 access token 无法撤回，
     *   它会在剩余 TTL（最多 15 分钟）内继续有效。
     *
     *   要让 access token 也立即失效，只有两条路：
     *     a) 维护吊销黑名单(Redis)，每个请求都查。但这把无状态彻底破坏了，
     *        网关每个请求多一次 Redis 往返
     *     b) 把 TTL 缩到极短(比如 1 分钟)。但刷新频率飙升，auth-service 压力变大
     *
     *   我选了 15 分钟 TTL + 不做黑名单：对外卖订餐场景，
     *   登出后 15 分钟内 token 仍可用的风险是可接受的。
     *   但如果是处理执法记录的系统，答案会完全不同，那里必须上黑名单，
     *   因为「撤销某个人的访问权」必须即时生效。
     *   安全设计没有标准答案，只有和威胁模型匹配的答案。
     */
    @Transactional
    public void logout(RefreshRequest request) {
        String hash = refreshTokenGenerator.hash(request.getRefreshToken());
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now());
            log.info("[登出] userId={} familyId={}", token.getUserId(), token.getFamilyId());
        });
        // 找不到也静默返回成功：登出接口不应泄露「这个 token 是否存在」
    }

    // ------------------------------------------------------------------
    private AuthResponse issueTokens(User user, UUID familyId) {
        String accessToken = jwtIssuer.issue(user.getId(), user.getUsername(), user.getRole().name());

        String rawRefresh = refreshTokenGenerator.generate();
        refreshTokenRepository.save(RefreshToken.builder()
                .tokenHash(refreshTokenGenerator.hash(rawRefresh))   // 只存哈希
                .userId(user.getId())
                .familyId(familyId)
                .expiresAt(Instant.now().plus(refreshTokenTtl))
                .build());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)   // 原文只在这一次返回给客户端，服务端不留存
                .tokenType("Bearer")
                .expiresInSeconds(jwtIssuer.getAccessTokenTtlSeconds())
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole().name())
                .build();
    }
}
