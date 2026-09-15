package com.platform.iot.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 独立链拒绝浏览器JWT及明文连接；适配器凭据仅授权URL内的单个目标。 */
@Configuration
public class AdapterConfigurationSecurity {
    @Bean @Order(1)
    public SecurityFilterChain adapterConfigurationChain(HttpSecurity http,ProtocolPublicationService service,ObjectMapper mapper) throws Exception {
        http.securityMatcher("/v1/adapter-configurations/**");
        http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(a->a.anyRequest().hasAuthority("ADAPTER_CONFIG"));
        http.addFilterBefore(new TargetAuthenticationFilter(service,mapper),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    /** 未信任代理头不能声明HTTPS；TLS终止部署须由服务器配置受信代理转发策略。 */
    private static final class TargetAuthenticationFilter extends OncePerRequestFilter {
        private final ProtocolPublicationService service;
        private final ObjectMapper mapper;
        private TargetAuthenticationFilter(ProtocolPublicationService service,ObjectMapper mapper){this.service=service;this.mapper=mapper;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            String path=request.getRequestURI().substring(request.getContextPath().length());
            String prefix="/v1/adapter-configurations/";
            String rest=path.startsWith(prefix)?path.substring(prefix.length()):"";
            String[] parts=rest.split("/",-1);
            boolean route=parts.length==1&&"GET".equals(request.getMethod())||parts.length==2&&"receipts".equals(parts[1])&&"POST".equals(request.getMethod());
            try {
                if(!request.isSecure()||!route||!parts[0].matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
                service.authenticate(parts[0],request.getHeader("X-Adapter-Key"));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(parts[0],null,List.of(new SimpleGrantedAuthority("ADAPTER_CONFIG"))));
            } catch(RuntimeException failure) {
                SecurityContextHolder.clearContext();
                response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getOutputStream(),Map.of("success",false,"code",401,"msg","适配器认证失败或连接未加密"));
                return;
            }
            chain.doFilter(request,response);
        }
    }
}
