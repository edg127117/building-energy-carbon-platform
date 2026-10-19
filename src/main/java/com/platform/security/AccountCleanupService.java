package com.platform.security;

import com.platform.cache.CacheConstants;
import com.platform.cache.TokenCacheService;
import com.platform.system.mapper.SysUserMapper;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/** 安全变更的持久化缓存补偿；MySQL 提交决定撤权，Redis 清理不再撤销已提交的安全状态。 */
@Service
public class AccountCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AccountCleanupService.class);
    private final JdbcTemplate jdbc;
    private final SysUserMapper users;
    private final TokenCacheService tokens;
    private final JwtService jwt;
    private final StringRedisTemplate redis;
    private final TransactionTemplate retryTransaction;
    @org.springframework.beans.factory.annotation.Value("${security.auth-cleanup-enabled:true}")
    private boolean enabled = true;

    public AccountCleanupService(JdbcTemplate jdbc, SysUserMapper users, TokenCacheService tokens,
                                 JwtService jwt, StringRedisTemplate redis, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.users = users;
        this.tokens = tokens;
        this.jwt = jwt;
        this.redis = redis;
        retryTransaction = new TransactionTemplate(tm);
        retryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        retryTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        retryTransaction.setTimeout(10);
    }

    /** 调用方已持有账号锁；待办必须与安全版本同事务提交，进程中断后仍可重试。 */
    public void enqueue(Long userId, long revokedThroughVersion) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("账号缓存补偿必须与安全变更同事务保存");
        }
        jdbc.update("""
                INSERT INTO sys_auth_cleanup(user_id,revoked_through_version) VALUES (?,?)
                ON DUPLICATE KEY UPDATE revoked_through_version=GREATEST(revoked_through_version,?),
                    attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL
                """, userId, revokedThroughVersion, revokedThroughVersion);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { attempt(userId); }
        });
    }

    /** 每批有界扫描；测试环境关闭调度，失败保留待办，应用重启不丢失补偿。 */
    @Scheduled(fixedDelayString = "${security.auth-cleanup-delay-ms:30000}", initialDelay = 30000)
    public void retryPending() {
        if (!enabled) return;
        try {
            for (Long userId : jdbc.queryForList("""
                    SELECT user_id FROM sys_auth_cleanup WHERE next_attempt_at<=CURRENT_TIMESTAMP
                    ORDER BY next_attempt_at LIMIT 20
                    """, Long.class)) attempt(userId);
        } catch (RuntimeException failure) {
            log.warn("账号缓存补偿扫描失败，保留持久化待办");
        }
    }

    /** 独立事务避免 afterCommit 复用已结束事务；统一先账号后待办锁，允许多节点重复触发。 */
    public void attempt(Long userId) {
        try {
            retryTransaction.executeWithoutResult(status -> {
                users.lockById(userId);
                var versions = jdbc.queryForList(
                        "SELECT revoked_through_version FROM sys_auth_cleanup WHERE user_id=? FOR UPDATE",
                        Long.class, userId);
                if (versions.isEmpty()) return;
                try {
                    String token = tokens.getFromWhitelist(userId);
                    if (token != null && isObsolete(token, versions.getFirst())) {
                        // 即使超时命令延迟执行，也只删除读到的旧值，不能误删随后登录的新 Token。
                        tokens.removeFromWhitelistIfMatches(userId, token);
                    }
                    redis.delete(CacheConstants.MENU_USER + userId);
                    redis.delete(CacheConstants.MENU_ALL);
                } catch (RuntimeException failure) {
                    jdbc.update("""
                            UPDATE sys_auth_cleanup SET attempts=attempts+1,last_error='REDIS_CLEANUP_FAILED',
                                next_attempt_at=? WHERE user_id=?
                            """, Timestamp.valueOf(LocalDateTime.now().plusSeconds(30)), userId);
                    log.warn("账号安全变更已生效，缓存清理待重试: userId={}", userId);
                    return;
                }
                jdbc.update("DELETE FROM sys_auth_cleanup WHERE user_id=?", userId);
            });
        } catch (RuntimeException failure) {
            // 包括补偿提交失败；不得把主事务已成功的禁用/撤权响应改成失败。
            log.warn("账号缓存补偿未完成，保留待办重试: userId={}", userId);
        }
    }

    private boolean isObsolete(String token, long revokedThroughVersion) {
        try {
            Long version = jwt.getSecurityVersion(jwt.parseToken(token).getPayload());
            // 撤权版本本身没有签发会话；包含失败登录可能遗留的同版本缓存，新登录版本必定更大。
            return version == null || version <= revokedThroughVersion;
        } catch (JwtException | IllegalArgumentException invalid) {
            return true;
        }
    }
}
