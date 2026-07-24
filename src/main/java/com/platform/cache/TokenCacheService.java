package com.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Token 缓存服务
 * 冻结书 D-010：Redis 用于 Token 缓存
 *
 * 本期用途：
 * - 白名单：登录成功后将 userId→token 写入 Redis，配合 JWT 过期时间设置 TTL
 * - 黑名单：登出/踢人时将 token 加入黑名单，Filter 检测到黑名单直接拒登（预留）
 *
 * 设计要点：
 * - 白名单 TTL 与 JWT expire-seconds 对齐，自动过期无需手动清理
 * - 黑名单 TTL 设为 JWT 剩余有效期（token 过期后黑名单也过期，自动释放内存）
 * - 降级：Redis 不可用时不影响登录（白名单写失败只打日志，不影响 Token 生成）
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

    /**
     * 将 Token 加入白名单
     * 登录成功后调用，TTL 与 JWT 过期时间对齐
     */
    public void addToWhitelist(Long userId, String token) {
        String key = CacheConstants.TOKEN_WHITELIST + userId;
        try {
            redis.opsForValue().set(key, token, Duration.ofSeconds(jwtExpireSeconds));
            log.debug("Token 白名单写入: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 白名单写入失败: userId={}", userId, e);
        }
    }

    /**
     * 从白名单获取 Token（校验当前请求 Token 是否与登录时的一致）
     */
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

    /**
     * 从白名单移除（登出时调用）
     */
    public void removeFromWhitelist(Long userId) {
        try {
            redis.delete(CacheConstants.TOKEN_WHITELIST + userId);
            log.debug("Token 白名单移除: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 白名单移除失败: userId={}", userId, e);
        }
    }

    // ──────────── 黑名单（登出/踢人时写入）────────────

    /**
     * 将 Token 加入黑名单（登出或强制踢人时调用）
     * TTL = token 剩余有效期，过期后自动清除
     */
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

    /**
     * 判断 Token 是否在黑名单中
     */
    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(CacheConstants.TOKEN_BLACKLIST + token));
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，Token 黑名单校验失败，默认放行", e);
            return false; // Redis 不可用时默认放行，避免大面积误拦截
        }
    }
}
