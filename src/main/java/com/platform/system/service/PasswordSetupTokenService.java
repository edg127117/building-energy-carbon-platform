package com.platform.system.service;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.cache.TokenCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
/**
 * 签发和消费账号激活/密码重置令牌。
 *
 * <p>MySQL 只保存 SHA-256，原始令牌只返回给已批准命令的当前调用方。消费使用行锁并在同一事务中
 * 更新 BCrypt 密码、令牌状态和审计；激活令牌会启用新账号，重置令牌不会改变既有停用状态。</p>
 */
public class PasswordSetupTokenService {
    public static final String INVALID_TOKEN = "PASSWORD_SETUP_TOKEN_INVALID";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenCacheService tokenCacheService;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties properties;

    /** 签发令牌前撤销该账号尚未消费的旧令牌，避免多个有效入口并存。 */
    @Transactional(rollbackFor = Exception.class)
    public IssuedToken issue(long userId, String sourceRequestId, long operatorId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "账号不存在或已删除");
        Purpose purpose = user.getActivationPending() != null && user.getActivationPending() == 1
                ? Purpose.ACTIVATION : Purpose.RESET;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(properties.getPasswordTokenTtl());
        jdbcTemplate.update("""
                UPDATE sys_password_setup_token SET status='REVOKED'
                WHERE user_id=? AND status='ACTIVE'
                """, userId);
        String rawToken = newRawToken();
        jdbcTemplate.update("""
                INSERT INTO sys_password_setup_token
                (token_id,user_id,token_hash,purpose,status,expires_at,source_request_id,created_by,created_at)
                VALUES (?,?,?,?,'ACTIVE',?,?,?,?)
                """, id(), userId, hash(rawToken), purpose.name(), Timestamp.valueOf(expiresAt),
                sourceRequestId, operatorId, Timestamp.valueOf(now));
        return new IssuedToken(rawToken, purpose.name(), expiresAt);
    }

    /** 原始令牌只能成功消费一次；任何无效、过期或已用令牌统一返回同一脱敏错误。 */
    @Transactional(rollbackFor = Exception.class)
    public void setupPassword(String rawToken, String password) {
        TokenRecord token = findForUpdate(hash(rawToken));
        LocalDateTime now = LocalDateTime.now();
        if (token == null || !"ACTIVE".equals(token.status()) || !token.expiresAt().isAfter(now)) {
            throw invalidToken();
        }
        SysUser user = userMapper.selectById(token.userId());
        if (user == null) throw invalidToken();
        Purpose purpose = Purpose.valueOf(token.purpose());
        if (purpose == Purpose.ACTIVATION
                && (user.getActivationPending() == null || user.getActivationPending() != 1)) {
            throw invalidToken();
        }
        user.setPassword(passwordEncoder.encode(password));
        if (purpose == Purpose.ACTIVATION) {
            user.setActivationPending(0);
            user.setStatus(1);
        }
        userMapper.updateById(user);
        int consumed = jdbcTemplate.update("""
                UPDATE sys_password_setup_token SET status='USED',used_at=?
                WHERE token_id=? AND status='ACTIVE'
                """, Timestamp.valueOf(now), token.tokenId());
        if (consumed != 1) throw invalidToken();
        jdbcTemplate.update("""
                UPDATE sys_password_setup_token SET status='REVOKED'
                WHERE user_id=? AND token_id<>? AND status='ACTIVE'
                """, token.userId(), token.tokenId());
        tokenCacheService.revokeActiveToken(token.userId());
        auditWriter.append(new AuditEvidence("SYSTEM_SECURITY", null, "USER", token.userId(),
                purpose == Purpose.ACTIVATION ? "ACTIVATE_USER_CREDENTIAL" : "RESET_USER_CREDENTIAL",
                "USER_ACCOUNT", Long.toString(token.userId()), null, token.sourceRequestId(),
                null, purpose == Purpose.ACTIVATION ? "credentialStatus=ACTIVE" : "credentialStatus=RESET",
                "SUCCESS", null, TraceContext.current(), now, properties.getEnvironmentMode(), false));
    }

    private TokenRecord findForUpdate(String tokenHash) {
        List<TokenRecord> values = jdbcTemplate.query("""
                SELECT token_id,user_id,purpose,status,expires_at,source_request_id
                FROM sys_password_setup_token WHERE token_hash=? FOR UPDATE
                """, (rs, rowNum) -> new TokenRecord(rs.getString("token_id"), rs.getLong("user_id"),
                rs.getString("purpose"), rs.getString("status"),
                rs.getTimestamp("expires_at").toLocalDateTime(), rs.getString("source_request_id")), tokenHash);
        return values.stream().findFirst().orElse(null);
    }

    private static BusinessException invalidToken() {
        return new BusinessException(400, INVALID_TOKEN, "一次性密码令牌无效或已过期");
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw invalidToken();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record IssuedToken(String token, String purpose, LocalDateTime expiresAt) {
    }

    private record TokenRecord(String tokenId, long userId, String purpose, String status,
                               LocalDateTime expiresAt, String sourceRequestId) {
    }

    private enum Purpose {
        ACTIVATION,
        RESET
    }
}
