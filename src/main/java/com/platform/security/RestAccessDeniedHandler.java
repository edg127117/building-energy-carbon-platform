package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.SecurityAuditService;
import com.platform.audit.TraceContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
/**
 * Spring Security 对“身份有效但权限不足”的请求返回 403 的处理器。
 *
 * <p>主要承接路由或方法角色校验拒绝，与未登录的 401 分开。建筑范围越权由业务服务抛出
 * 403 {@code BusinessException}，虽然经过不同入口，二者对前端保持一致的错误字段。</p>
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final SecurityAuditService securityAuditService;

    public RestAccessDeniedHandler(ObjectMapper objectMapper, SecurityAuditService securityAuditService) {
        this.objectMapper = objectMapper;
        this.securityAuditService = securityAuditService;
    }

    /** 写入统一 403 JSON，供前端展示无权限状态而不是误清登录 Token。 */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 403);
        body.put("msg", "无权限访问");
        body.put("success", false);
        body.put("traceId", TraceContext.from(request));
        addVersionedErrorCode(request, body);
        Long operatorId = null;
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                instanceof JwtUserPrincipal principal) {
            operatorId = principal.getId();
        }
        securityAuditService.recordDenied(request, operatorId, "ROLE_DENIED");

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
        } else if (path.startsWith(request.getContextPath() + "/v1/data-sources")
                || path.startsWith(request.getContextPath() + "/v1/collection-")) {
            body.put("errorCode", "COLLECTION_CONFIG_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/quality-usage")) {
            body.put("errorCode", "QUALITY_POLICY_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/relation-models")) {
            body.put("errorCode", "RELATION_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-point-profiles")) {
            body.put("errorCode", "ENERGY_METADATA_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-catalog")) {
            body.put("errorCode", "ENERGY_CATALOG_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-conversion")) {
            body.put("errorCode", "ENERGY_CONVERSION_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-activity-data")) {
            body.put("errorCode", "ENERGY_ACTIVITY_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-aggregation")) {
            body.put("errorCode", "ENERGY_AGGREGATION_FORBIDDEN");
        } else if (path.startsWith(request.getContextPath() + "/v1/backoffice")) {
            body.put("errorCode", "BACKOFFICE_OPERATION_FORBIDDEN");
        }
    }
}
