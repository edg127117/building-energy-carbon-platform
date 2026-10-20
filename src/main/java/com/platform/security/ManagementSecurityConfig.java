package com.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.transaction.TransactionException;

/** 运行管理只接受独立回环端口；指标另查当前后台职责，不把业务管理员等同于运维。 */
@Configuration(proxyBeanMethods = false)
public class ManagementSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http, Environment environment,
            OperationsDutyService duties, JwtAuthenticationFilter jwt,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler denied) throws Exception {
        validateListener(environment);
        http.securityMatcher(new OrRequestMatcher(EndpointRequest.toAnyEndpoint(),
                new AntPathRequestMatcher("/actuator"), new AntPathRequestMatcher("/actuator/**"),
                new AntPathRequestMatcher("/api/actuator"), new AntPathRequestMatcher("/api/actuator/**")));
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied));
        http.authorizeHttpRequests(auth -> auth
                // 即使反代错误转发，业务监听端口也不能承载管理端点；不信任 Forwarded 头声明的端口。
                .requestMatchers(request -> !onManagementPort(request, environment)).denyAll()
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                .requestMatchers(EndpointRequest.to(MetricsEndpoint.class)).access((authentication, context) -> {
                    var current = authentication.get();
                    if (!(current.getPrincipal() instanceof JwtUserPrincipal principal)) {
                        return new AuthorizationDecision(false);
                    }
                    try {
                        return new AuthorizationDecision(duties.canObserve(principal.getId()));
                    } catch (DataAccessException | TransactionException failure) {
                        // 职责库故障不能降级为角色放行；复用登录态依赖故障的脱敏 503 响应。
                        context.getRequest().setAttribute("auth_state_unavailable", true);
                        throw new InsufficientAuthenticationException("运行管理授权服务暂不可用");
                    }
                })
                .anyRequest().denyAll());
        http.addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static void validateListener(Environment environment) {
        int port = environment.getRequiredProperty("management.server.port", Integer.class);
        int businessPort = environment.getProperty("server.port", Integer.class, 8080);
        String address = environment.getRequiredProperty("management.server.address");
        if (port < 0 || port > 65535 || (port != 0 && port == businessPort)
                || !("127.0.0.1".equals(address) || "::1".equals(address))) {
            throw new IllegalStateException("运行管理必须使用独立端口和回环地址");
        }
    }

    private static boolean onManagementPort(HttpServletRequest request, Environment environment) {
        int port = environment.getProperty("local.management.port", Integer.class,
                environment.getRequiredProperty("management.server.port", Integer.class));
        return request.getLocalPort() == port;
    }
}
