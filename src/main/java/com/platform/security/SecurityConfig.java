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
 * <p>负责全局无状态安全链、JWT 过滤器顺序以及 401/403 JSON 响应。
 * 具体四角色权限由控制器上的 {@code @PreAuthorize} 定义，建筑数据范围由业务服务校验。</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 密码加密器
     * 说明：注册时写入 BCrypt 哈希；老数据若是明文密码，会在首次登录成功后自动升级为 BCrypt。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤链
     * - /auth/** 放行：用于注册/登录
     * - 其它接口默认需要登录（携带 Bearer Token）
     * - 使用 JWT 无状态鉴权，因此关闭 Session
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
                // 2. 现有大屏 WebSocket 暂时放行；正式多建筑推送前需补 JWT 握手和建筑订阅隔离。
                .requestMatchers("/ws/**", "/api/ws/**").permitAll()
                // 3. 其他所有请求必须携带 Token 并通过鉴权
                .anyRequest().authenticated()
        );

        // 在用户名密码过滤器之前，先做 JWT 解析与上下文注入
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
