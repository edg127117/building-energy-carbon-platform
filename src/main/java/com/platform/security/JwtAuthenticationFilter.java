package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把 Bearer JWT 转换成 Spring Security 当前用户的请求过滤器。
 *
 * <p>请求先在这里校验 JWT 签名、有效期和四类正式角色，再由
 * {@link TokenCacheService} 检查 Redis 中的单账号登录态。通过后写入的
 * {@link JwtUserPrincipal} 和 {@code ROLE_*} 权限会被控制器的 {@code @PreAuthorize}
 * 使用；建筑范围不在 JWT 中，由业务服务按 MySQL 授权单独校验。</p>
 *
 * <p>Redis 明确返回白名单不匹配或黑名单命中时，Token 视为失效；只有 Redis
 * 访问失败时才降级为仅信任已通过密码学校验的 JWT，避免缓存故障阻断全部请求。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenCacheService tokenCacheService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, TokenCacheService tokenCacheService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.tokenCacheService = tokenCacheService;
        this.objectMapper = objectMapper;
    }

    /**
     * 为一次 HTTP 请求建立认证上下文。
     *
     * <p>无 Bearer 头时直接交给安全链决定该路由是否允许匿名访问。Token 无效时只清空
     * 上下文并记录失败原因，仍继续过滤链：公开登录接口可以正常处理，受保护接口最终由
     * {@link RestAuthenticationEntryPoint} 返回 401。</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 只认 Bearer Token，其它情况交给后续链路处理（最终由 SecurityConfig 决定是否放行）
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 取出 Token 原文
        String token = authHeader.substring(7);

        try {
            // parseToken 同时校验签名和过期时间，不能只解码 claims 后直接建立身份。
            Jws<Claims> jws = jwtService.parseToken(token);
            Claims claims = jws.getPayload();
            String username = claims.getSubject();
            Long userId = jwtService.getUserId(claims);
            List<String> roles = jwtService.getRoles(claims).stream()
                    .map(String::toUpperCase)
                    .filter(FormalRole::isFormal)
                    .distinct()
                    .toList();
            if (userId == null || roles.isEmpty()) {
                throw new IllegalArgumentException("Token 不包含有效的正式角色");
            }

            TokenValidationResult tokenState = tokenCacheService.validateActiveToken(userId, token);
            if (tokenState == TokenValidationResult.REJECTED) {
                throw new IllegalArgumentException("Token 已失效或已被其他登录替换");
            }

            // Spring Security 的 hasRole 会查找 ROLE_ 前缀，因此把正式角色转换为 ROLE_PLATFORM_ADMIN 等权限值。
            List<GrantedAuthority> authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .collect(Collectors.toList());

            // principal 只保留 userId/username，密码、手机号等账号资料不进入请求上下文。
            JwtUserPrincipal principal = new JwtUserPrincipal(userId, username);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // 写入上下文后，@PreAuthorize 才能识别角色
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // 认证成功后仍需继续执行路由规则、方法权限和业务数据范围校验。
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            // Token 无效时降级为匿名请求；是否返回 401 由当前路由的安全规则统一决定。
            SecurityContextHolder.clearContext();
            // 保留内部失败原因便于安全入口诊断，但响应仍使用统一脱敏文案。
            request.setAttribute("jwt_error", e.getMessage());
            // 不在过滤器直接写响应，否则携带过期 Token 的 /auth/login 也会被错误拦截。
            filterChain.doFilter(request, response);
        }
    }
}
