package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.SecurityAuditService;
import com.platform.audit.TraceContext;
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

@Component
/**
 * Spring Security 对未建立有效身份的受保护请求返回认证错误的入口。
 *
 * <p>无 Token、JWT 签名/有效期失败、Redis 明确判定 Token 已撤销，都会由过滤器留下匿名上下文，
 * 最终进入这里。响应保持 {@code code/msg/success} JSON，前端据此清理登录态并跳转登录页；
 * 公开 {@code /auth/**} 请求不会因附带失效 Token 而在过滤器中被提前拦截。</p>
 *
 * <p>Redis 登录态或 MySQL 权威状态无法核实时返回 503，不建立身份，也不误报为密码错误或要求前端清除会话。</p>
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final SecurityAuditService securityAuditService;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper, SecurityAuditService securityAuditService) {
        this.objectMapper = objectMapper;
        this.securityAuditService = securityAuditService;
    }

    /** 登录态依赖不可用返回 503，其余认证失败返回 401；响应不包含底层异常或 Token。 */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        Map<String, Object> body = new HashMap<>();
        boolean unavailable = Boolean.TRUE.equals(request.getAttribute("auth_state_unavailable"));
        body.put("code", unavailable ? 503 : 401);
        body.put("msg", unavailable ? "登录状态服务暂不可用，请稍后重试" : "未登录或登录已过期");
        body.put("success", false);
        body.put("traceId", TraceContext.from(request));
        if (unavailable) {
            body.put("errorCode", "AUTH_STATE_UNAVAILABLE");
        } else {
            addVersionedErrorCode(request, body);
        }
        String reason = unavailable ? "AUTH_STATE_UNAVAILABLE" : request.getAttribute("jwt_error") == null
                ? "AUTHENTICATION_REQUIRED" : "TOKEN_INVALID";
        securityAuditService.recordDenied(request, null, reason);

        response.setStatus(unavailable ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
                : HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 新版本管理 API 使用各自机器码，旧接口继续保持既有三字段响应。 */
    private static void addVersionedErrorCode(HttpServletRequest request, Map<String, Object> body) {
        String path = request.getRequestURI();
        if (path.startsWith(request.getContextPath() + "/v1/hvac-control")) {
            body.put("errorCode", "HVAC_CONTROL_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/hvac-monitoring")) {
            body.put("errorCode", "DAIKIN_MONITORING_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/device-products")
                || path.startsWith(request.getContextPath() + "/v1/device-onboarding")
                || path.startsWith(request.getContextPath() + "/v1/operations/device-onboarding")
                || path.startsWith(request.getContextPath() + "/v1/daikin/")) {
            body.put("errorCode", "ONBOARDING_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/assets")) {
            body.put("errorCode", "ASSET_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/data-sources")
                || path.startsWith(request.getContextPath() + "/v1/collection-")) {
            body.put("errorCode", "COLLECTION_CONFIG_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/quality-usage")) {
            body.put("errorCode", "QUALITY_POLICY_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/relation-models")) {
            body.put("errorCode", "RELATION_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-point-profiles")) {
            body.put("errorCode", "ENERGY_METADATA_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-catalog")) {
            body.put("errorCode", "ENERGY_CATALOG_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-conversion")) {
            body.put("errorCode", "ENERGY_CONVERSION_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-activity-data")) {
            body.put("errorCode", "ENERGY_ACTIVITY_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-aggregation")) {
            body.put("errorCode", "ENERGY_AGGREGATION_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-efficiency")) {
            body.put("errorCode", "EERP_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-periods")) {
            body.put("errorCode", "ENERGY_PERIOD_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-boundary-summaries")) {
            body.put("errorCode", "ENERGY_SUMMARY_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/carbon-management")
                || path.startsWith(request.getContextPath() + "/v1/carbon-projects")) {
            body.put("errorCode", "CARBON_UNAUTHORIZED");
        } else if (path.startsWith(request.getContextPath() + "/v1/backoffice")) {
            body.put("errorCode", "BACKOFFICE_OPERATION_UNAUTHORIZED");
        }
    }
}
