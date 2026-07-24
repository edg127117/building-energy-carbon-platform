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
 * JWT 颁发与解析服务
 * 约定：
 * - subject：用户名
 * - uid：用户ID
 * - roles：角色列表（配合 Spring Security 的 ROLE_ 前缀做 RBAC）
 */
@Service
public class JwtService {

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.expire-seconds:86400}")
    private long expireSeconds;

    /**
     * 生成 Token
     * 角色信息会写入 身份声明claims，供后续鉴权与 @PreAuthorize 使用。
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
     * 解析 Token（含签名校验与过期校验）
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

    /**
     * 从 claims 中提取角色列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List) {
            return (List<String>) roles;
        }
        return Collections.emptyList();
    }

    /**
     * 从 claims 中提取用户ID
     */
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
        // HS256/HS512 等 HMAC 算法要求密钥有足够长度，太短会降低安全性且可能直接报错
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("security.jwt.secret 长度不足，至少需要 32 字节");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
