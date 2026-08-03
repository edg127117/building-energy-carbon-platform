package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Security 对未建立有效身份的受保护请求返回 401 的入口。
 *
 * <p>无 Token、JWT 签名/有效期失败、Redis 明确判定 Token 已撤销，都会由过滤器留下匿名上下文，
 * 最终进入这里。响应保持 {@code code/msg/success} JSON，前端据此清理登录态并跳转登录页；
 * 公开 {@code /auth/**} 请求不会因附带失效 Token 而在过滤器中被提前拦截。</p>
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 写入脱敏的 401 JSON，不把 JWT 解析失败原因和安全异常细节暴露给客户端。 */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 401);
        body.put("msg", "未登录或登录已过期");
        body.put("success", false);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
