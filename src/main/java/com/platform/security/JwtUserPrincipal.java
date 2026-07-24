package com.platform.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 放入 Spring Security 上下文的用户身份最小信息
 * 说明：只保留 userId/username，避免把敏感字段（如密码、手机号）放进上下文。
 */
@Data
@AllArgsConstructor
public class JwtUserPrincipal {
    private Long id;
    private String username;
}
