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
 * JWT 鉴权过滤器
 * 作用：从请求头 {@code Authorization: Bearer <token>} 解析用户身份和正式角色，
 * 校验 Redis 当前登录态，并写入 Spring Security 上下文。
 *
 * <p>过滤器只建立“是谁、拥有什么角色”的认证上下文。建筑范围等数据权限由业务服务继续校验。
 * Redis 不可用时按设计降级为 JWT 签名与有效期校验；Redis 明确拒绝时则按未登录处理。</p>
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
            // 解析并校验 Token（含签名与过期时间）
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

            // principal 放入最小信息（userId/username），避免把敏感信息塞进上下文
            JwtUserPrincipal principal = new JwtUserPrincipal(userId, username);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // 写入上下文后，@PreAuthorize 才能识别角色
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // 解析成功，必须放行
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            // Token 无效时清空认证；是否返回 401 由后续安全链根据当前路由是否需要登录统一决定。

            // 1. 清空上下文，将其降级为“未登录的匿名用户”
            SecurityContextHolder.clearContext();
            // 2. 将真实的错误信息塞入 request，方便后续的 EntryPoint 获取真实死因
            request.setAttribute("jwt_error", e.getMessage());

            // 3. 绝对不要在这里 response.write 401！必须 doFilter 放行！
            // 如果请求是 /auth/login (permitAll)，由于不需要身份，Spring Security 会让它通过。
            // 例如访问受保护的 /api/hvac/buildings/{id}/snapshot 时，
            // Spring Security 会把没有有效 JWT 的请求交给统一 401 入口。
            filterChain.doFilter(request, response);
        }
    }
}
