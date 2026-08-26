package com.platform.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
/** 为每个 HTTP 请求生成不可预测的服务端追踪标识，并同步到响应头、MDC 和请求属性。 */
public class TraceIdFilter extends OncePerRequestFilter {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String traceId = HexFormat.of().formatHex(bytes);
        request.setAttribute(TraceContext.ATTRIBUTE, traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        MDC.put(TraceContext.MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
