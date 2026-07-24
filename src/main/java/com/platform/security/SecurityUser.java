package com.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从 Spring Security 上下文读取当前 JWT 用户的辅助类。
 *
 * <p>控制器通过本类统一取得用户 ID 和正式角色，避免在每个接口中重复解析
 * {@link Authentication}，也避免把 JWT 解析细节扩散到业务服务。</p>
 */
public final class SecurityUser {
    private SecurityUser() {}

    /**
     * 获取当前登录用户 ID。
     *
     * @throws IllegalStateException 当前请求没有经过 JWT 认证或 principal 类型不正确
     */
    public static Long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtUserPrincipal principal)) {
            throw new IllegalStateException("当前请求没有有效用户身份");
        }
        return principal.getId();
    }

    /**
     * 提取当前用户的角色键，并去掉 Spring Security 使用的 {@code ROLE_} 前缀。
     *
     * @return 不可修改的角色键集合，例如 {@code PLATFORM_ADMIN}
     */
    public static Set<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5)).collect(Collectors.toUnmodifiableSet());
    }
}
