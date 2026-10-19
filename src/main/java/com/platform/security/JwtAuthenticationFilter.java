package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@link AuthenticationStateService} 检查 Redis 登录态与 MySQL 当前账号状态和安全版本。通过后写入的
 * {@link JwtUserPrincipal} 和 {@code ROLE_*} 权限会被控制器的 {@code @PreAuthorize}
 * 使用；建筑范围不在 JWT 中，由业务服务按 MySQL 授权单独校验。</p>
 *
 * <p>Redis 明确拒绝时 Token 视为失效；Redis 或 MySQL 不可用时保持匿名并由安全入口返回
 * 服务不可用，不能仅凭 JWT 签名绕过撤销状态校验。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthenticationStateService authenticationState;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, AuthenticationStateService authenticationState, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.authenticationState = authenticationState;
        this.objectMapper = objectMapper;
    }

    /**
     * 为一次 HTTP 请求建立认证上下文。
     *
     * <p>无 Bearer 头时直接交给安全链决定该路由是否允许匿名访问。Token 无效时只清空
     * 上下文并记录失败原因，仍继续过滤链：公开登录接口可以正常处理，受保护接口最终由
     * {@link RestAuthenticationEntryPoint} 返回 401 或登录态依赖故障的 503。</p>
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

            authenticationState.requireActive(userId, token, jwtService.getSecurityVersion(claims));

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
        } catch (Exception e) {
            // 未建立身份时保持匿名，受保护路由由认证入口区分失效与依赖故障。
            SecurityContextHolder.clearContext();
            if (e instanceof com.platform.framework.exception.BusinessException failure
                    && Integer.valueOf(503).equals(failure.getCode())) {
                request.setAttribute("auth_state_unavailable", true);
            }
            // 保留内部失败原因便于安全入口诊断，但响应仍使用统一脱敏文案。
            request.setAttribute("jwt_error", e.getMessage());
            // 不在过滤器直接写响应，否则携带过期 Token 的 /auth/login 也会被错误拦截。
        }
        // 下游仅执行一次；业务异常不能触发重放已经执行过的请求。
        filterChain.doFilter(request, response);
    }
}
