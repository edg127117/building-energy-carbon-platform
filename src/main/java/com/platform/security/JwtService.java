package com.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 登录 Token 的签发与密码学校验边界。
 *
 * <p>Token 的 {@code subject} 保存用户名，{@code uid} 保存用户 ID，{@code roles}
 * 保存登录时的正式角色快照。JWT 只承载身份和角色，不承载建筑授权；建筑授权变更因此可在
 * 清理范围缓存后立即生效，而角色变更必须撤销旧 Token 并要求用户重新登录。</p>
 */
@Service
public class JwtService {

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.expire-seconds:86400}")
    private long expireSeconds;

    /**
     * 为已完成账号、密码和正式角色校验的用户签发 JWT。
     * 返回值由登录服务写入 Redis 单账号白名单并交给前端作为 Bearer Token。
     */
    public String generateToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expireSeconds * 1000);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(exp)
                .claim("uid", userId)
                .claim("roles", roles == null ? Collections.emptyList() : roles)
                .signWith(getKey())
                .compact();
    }

    /**
     * 验证 Token 的签名和有效期并返回 claims；校验失败直接抛出 JWT 异常，
     * 由认证过滤器把当前请求按未登录处理。
     */
    public Jws<Claims> parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token);
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    /** 从已验证 claims 中提取登录时的角色快照；字段缺失或类型错误时返回空集合。 */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List) {
            return (List<String>) roles;
        }
        return Collections.emptyList();
    }

    /** 从已验证 claims 中提取用户 ID；无法转换时返回 {@code null}，使过滤器拒绝建立身份。 */
    public Long getUserId(Claims claims) {
        Object uid = claims.get("uid");
        if (uid instanceof Number) {
            return ((Number) uid).longValue();
        }
        if (uid instanceof String) {
            try {
                return Long.parseLong((String) uid);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private SecretKey getKey() {
        // HMAC 密钥不足 32 字节时拒绝启动签发/校验，避免使用可被轻易猜测的弱密钥。
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("security.jwt.secret 长度不足，至少需要 32 字节");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
