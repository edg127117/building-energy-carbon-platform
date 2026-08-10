package com.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 核心配置。
 *
 * <p>负责全局无状态安全链、JWT 过滤器顺序以及 401/403 JSON 响应。这里仅定义匿名入口和
 * “其余请求必须认证”的粗粒度边界；四角色权限由控制器上的 {@code @PreAuthorize} 执行，
 * 建筑范围由服务层根据 MySQL 授权执行。前端菜单和路由隐藏都不能替代这两层后端校验。</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 注册和管理员重置密码统一写入 BCrypt；历史明文仅由登录服务在校验成功后升级。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 装配无状态 HTTP 安全链。
     *
     * <p>{@code /auth/**} 允许匿名注册和登录；WebSocket 路径只为浏览器 HTTP 升级匿名放行，
     * 业务权限由端点首帧 JWT 校验和建筑订阅校验执行；其余 HTTP 请求必须先由 JWT 过滤器建立
     * 身份。认证失败交给 401 入口，已认证但角色不足交给 403 处理器，业务层抛出的建筑越权则
     * 由全局异常处理器映射为 403。</p>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler
    ) throws Exception {
        // 前后端分离，采用 JWT，无需 CSRF
        http.csrf(csrf -> csrf.disable());
        // 无状态：不创建/不使用 HttpSession
        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // 401/403 统一返回 JSON，避免前端出现 HTML 或默认跳转
        http.exceptionHandling(eh -> eh
                .authenticationEntryPoint(restAuthenticationEntryPoint)
                .accessDeniedHandler(restAccessDeniedHandler)
        );

        // 路由级别的粗粒度权限：方法级细粒度用 @PreAuthorize
        http.authorizeHttpRequests(auth -> auth
                // 1. 放行登录与注册相关接口
                .requestMatchers("/auth/**", "/api/auth/**").permitAll()
                // 仅放行 WebSocket 传输升级；首帧 SUBSCRIBE 在端点内完成 JWT 与建筑权限校验。
                .requestMatchers("/ws/**", "/api/ws/**").permitAll()
                // 3. 其他所有请求必须携带 Token 并通过鉴权
                .anyRequest().authenticated()
        );

        // 在用户名密码过滤器之前，先做 JWT 解析与上下文注入
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
