package com.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 中单账号登录态和 Token 撤销记录的适配器。
 *
 * <p>白名单以用户 ID 保存最后一次登录签发的 Token，因此新登录会使旧 Token 失效；黑名单
 * 保存已登出或被管理员撤销的 Token。两类键的 TTL 都不超过 JWT 有效期，避免 Token 过期后
 * 留下永久状态。认证过滤器通过三态结果区分“确实失效”和“Redis 故障”。</p>
 *
 * <p>Redis 访问失败只记录告警：登录仍可签发 JWT，请求鉴权退化为签名和有效期校验。
 * 这意味着缓存故障期间无法保证立即踢下线，是可用性优先降级的明确代价。</p>
 */
@Service
public class TokenCacheService {

    private static final Logger log = LoggerFactory.getLogger(TokenCacheService.class);

    private final StringRedisTemplate redis;
    private final long jwtExpireSeconds; // 默认 86400 秒 (24h)

    public TokenCacheService(StringRedisTemplate redis,
                             @org.springframework.beans.factory.annotation.Value("${security.jwt.expire-seconds:86400}") long jwtExpireSeconds) {
        this.redis = redis;
        this.jwtExpireSeconds = jwtExpireSeconds;
    }

    // ──────────── 白名单（登录时写入）────────────

    /** 登录成功后覆盖用户白名单，使该账号只保留最后签发的一个有效 Token。 */
    public void addToWhitelist(Long userId, String token) {
        String key = CacheConstants.TOKEN_WHITELIST + userId;
        try {
            redis.opsForValue().set(key, token, Duration.ofSeconds(jwtExpireSeconds));
            log.debug("Token 白名单写入: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 白名单写入失败: userId={}", userId, e);
        }
    }

    /** 读取用户白名单原值；返回 {@code null} 可能是未命中，也可能是 Redis 读取失败。 */
    public String getFromWhitelist(Long userId) {
        try {
            return redis.opsForValue().get(CacheConstants.TOKEN_WHITELIST + userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 白名单读取失败: userId={}", userId, e);
            return null;
        }
    }

    /**
     * 校验单账号当前有效 Token。
     *
     * <p>系统采用“单账号单有效 Token”：同一用户再次登录会覆盖白名单中的旧 Token。
     * 本方法同时检查黑名单和白名单，并明确区分权限拒绝与 Redis 故障。</p>
     */
    public TokenValidationResult validateActiveToken(Long userId, String token) {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(CacheConstants.TOKEN_BLACKLIST + token))) {
                return TokenValidationResult.REJECTED;
            }
            String active = redis.opsForValue().get(CacheConstants.TOKEN_WHITELIST + userId);
            return token != null && token.equals(active)
                    ? TokenValidationResult.ACTIVE
                    : TokenValidationResult.REJECTED;
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 降级为仅校验 JWT: userId={}", userId, e);
            return TokenValidationResult.CACHE_UNAVAILABLE;
        }
    }

    /**
     * 注销某用户当前登录态。
     *
     * <p>角色变更、账号禁用、逻辑删除和管理员重置安全信息时调用。当前白名单 Token
     * 会进入黑名单，然后删除白名单项，使已经签发的 JWT 立即失效。</p>
     */
    public void revokeActiveToken(Long userId) {
        try {
            String token = redis.opsForValue().get(CacheConstants.TOKEN_WHITELIST + userId);
            if (token != null) {
                redis.opsForValue().set(CacheConstants.TOKEN_BLACKLIST + token,
                        String.valueOf(System.currentTimeMillis()), jwtExpireSeconds, TimeUnit.SECONDS);
            }
            redis.delete(CacheConstants.TOKEN_WHITELIST + userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，无法注销用户当前 Token: userId={}", userId, e);
        }
    }

    /** 删除当前用户白名单项；登出流程会先把原 Token 加入黑名单。 */
    public void removeFromWhitelist(Long userId) {
        try {
            redis.delete(CacheConstants.TOKEN_WHITELIST + userId);
            log.debug("Token 白名单移除: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 白名单移除失败: userId={}", userId, e);
        }
    }

    // ──────────── 黑名单（登出/踢人时写入）────────────

    /** 按 JWT 剩余秒数记录撤销 Token，供认证过滤器在自然过期前拒绝它。 */
    public void addToBlacklist(String token, long remainingSeconds) {
        try {
            redis.opsForValue().set(
                    CacheConstants.TOKEN_BLACKLIST + token,
                    String.valueOf(System.currentTimeMillis()),
                    remainingSeconds,
                    TimeUnit.SECONDS
            );
            log.debug("Token 已加入黑名单，剩余有效期: {}秒", remainingSeconds);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 黑名单写入失败", e);
        }
    }

    /** 查询黑名单；Redis 故障时返回 {@code false}，与系统的 JWT 降级策略一致。 */
    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(CacheConstants.TOKEN_BLACKLIST + token));
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 黑名单校验失败，默认放行", e);
            return false; // Redis 不可用时默认放行，避免大面积误拦截
        }
    }
}
