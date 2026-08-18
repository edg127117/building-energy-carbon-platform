package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Security 对“身份有效但权限不足”的请求返回 403 的处理器。
 *
 * <p>主要承接路由或方法角色校验拒绝，与未登录的 401 分开。建筑范围越权由业务服务抛出
 * 403 {@code BusinessException}，虽然经过不同入口，二者对前端保持一致的错误字段。</p>
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 写入统一 403 JSON，供前端展示无权限状态而不是误清登录 Token。 */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 403);
        body.put("msg", "无权限访问");
        body.put("success", false);
        addVersionedErrorCode(request, body);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 新版本管理 API 使用各自机器码，旧接口继续保持既有三字段响应。 */
    private static void addVersionedErrorCode(HttpServletRequest request, Map<String, Object> body) {
        String path = request.getRequestURI();
        if (path.startsWith(request.getContextPath() + "/v1/device-products")
                || path.startsWith(request.getContextPath() + "/v1/device-onboarding")) {
            body.put("errorCode", "ONBOARDING_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/assets")) {
            body.put("errorCode", "ASSET_FORBIDDEN");
        }
    }
}
