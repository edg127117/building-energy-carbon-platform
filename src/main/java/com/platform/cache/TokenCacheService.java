package com.platform.cache;

import com.platform.framework.exception.BusinessException;
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
 * <p>Redis 不可用时不能证明登录态有效，HTTP 与实时订阅均拒绝授权。
 * 登录写入失败向上抛出；安全变更由 MySQL 版本决定生效，缓存清理由持久化补偿重试。</p>
 */
@Service
public class TokenCacheService {
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> DELETE_MATCHING =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

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
            log.warn("Redis 不可用，Token 白名单写入失败: userId={}", userId);
            throw unavailable();
        }
    }

    /** 读取用户白名单原值；未命中返回 {@code null}，依赖故障向上抛出。 */
    public String getFromWhitelist(Long userId) {
        try {
            return redis.opsForValue().get(CacheConstants.TOKEN_WHITELIST + userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 白名单读取失败: userId={}", userId);
            throw unavailable();
        }
    }

    /** 原子比较并删除，迟到的补偿命令不能移除同账号后来登录的新白名单。 */
    public void removeFromWhitelistIfMatches(Long userId, String expectedToken) {
        redis.execute(DELETE_MATCHING, java.util.List.of(CacheConstants.TOKEN_WHITELIST + userId), expectedToken);
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
            log.warn("Redis 不可用，无法确认 Token 登录态: userId={}", userId);
            return TokenValidationResult.CACHE_UNAVAILABLE;
        }
    }

    /**
     * 注销某用户当前登录态。
     *
     * <p>低层缓存撤销工具，不负责数据库安全状态；领域安全变更使用持久化补偿。当前白名单 Token
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
            log.warn("Redis 不可用，无法注销用户当前 Token: userId={}", userId);
            throw unavailable();
        }
    }

    /** 删除当前用户白名单项；登出流程会先把原 Token 加入黑名单。 */
    public void removeFromWhitelist(Long userId) {
        try {
            redis.delete(CacheConstants.TOKEN_WHITELIST + userId);
            log.debug("Token 白名单移除: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 白名单移除失败: userId={}", userId);
            throw unavailable();
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
            log.warn("Redis 不可用，Token 黑名单写入失败");
            throw unavailable();
        }
    }

    /** 查询黑名单；依赖故障向上抛出，不能把未完成的查询当作未撤销。 */
    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(CacheConstants.TOKEN_BLACKLIST + token));
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 黑名单校验失败");
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(503, "AUTH_STATE_UNAVAILABLE", "登录状态服务暂不可用，请稍后重试");
    }
}
