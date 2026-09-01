package com.platform.framework.exception;

import com.platform.audit.SecurityAuditService;
import com.platform.audit.TraceContext;
import com.platform.security.JwtUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
/**
 * 将 Controller 和 Spring MVC 异常转换为稳定、脱敏的 JSON 响应。
 *
 * <p>已知的认证、权限、参数、业务和路径不存在异常分别返回对应 HTTP 状态；
 * 只有无法分类的异常才进入 500 兜底，避免把普通 404 误报为系统故障。</p>
 */
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final SecurityAuditService securityAuditService;

    public GlobalExceptionHandler(SecurityAuditService securityAuditService) {
        this.securityAuditService = securityAuditService;
    }

    /**
     * 把 Bean Validation、绑定和参数类型错误统一转换为 400。
     * 具体框架异常不直接返回前端，避免泄露字段绑定和内部类型信息。
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidationException(Exception e, HttpServletRequest request) {
        log.warn("前端非法输入拦截: {}", e.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("code", 400);
        response.put("msg", "您输入的格式不正确，请检查后重试");
        response.put("success", false);
        addTraceId(response, request);
        securityAuditService.recordDenied(request, null, "AUTHENTICATION_REQUIRED");
        addVersionedErrorCode(response, request,
                "ONBOARDING_VALIDATION_FAILED", "ASSET_VALIDATION_FAILED",
                "COLLECTION_CONFIG_VALIDATION_FAILED", "QUALITY_POLICY_VALIDATION_FAILED",
                "RELATION_VALIDATION_FAILED", "ENERGY_METADATA_VALIDATION_FAILED",
                "ENERGY_CATALOG_VALIDATION_FAILED", "ENERGY_CONVERSION_VALIDATION_FAILED",
                "ENERGY_ACTIVITY_VALIDATION_FAILED");
        return response;
    }
    /**
     * 把 Service 抛出的脱敏业务码映射为相同语义的 HTTP 状态。
     * 401/403 供前端区分重新登录和权限不足，404/409 表示资源或状态冲突，503 表示依赖暂不可用。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(
            BusinessException e, HttpServletRequest request) {
        log.info("业务逻辑阻断: {}", e.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("code", e.getCode());
        response.put("msg", e.getMessage());
        response.put("success", false);
        addTraceId(response, request);
        if (e.getErrorCode() != null) {
            response.put("errorCode", e.getErrorCode());
        }
        if (e.getCode() == 403) {
            Long operatorId = currentUserId();
            securityAuditService.recordDenied(request, operatorId,
                    e.getErrorCode() == null ? "BUILDING_SCOPE_DENIED" : e.getErrorCode());
        }
        HttpStatus status = switch (e.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            // HVAC 查询在 TDengine/JDBC 暂不可用时使用业务码 503，
            // 映射为标准服务不可用状态，同时保留 Service 提供的脱敏错误文案。
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(response);
    }

    /** 处理进入 MVC 后抛出的认证异常；安全过滤链之前的失败由认证入口返回同结构 401。 */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, Object> handleAuthenticationException(
            AuthenticationException e, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 401);
        response.put("msg", "未登录或登录已过期");
        response.put("success", false);
        addTraceId(response, request);
        addVersionedErrorCode(response, request,
                "ONBOARDING_UNAUTHORIZED", "ASSET_UNAUTHORIZED",
                "COLLECTION_CONFIG_UNAUTHORIZED", "QUALITY_POLICY_UNAUTHORIZED",
                "RELATION_UNAUTHORIZED", "ENERGY_METADATA_UNAUTHORIZED",
                "ENERGY_CATALOG_UNAUTHORIZED", "ENERGY_CONVERSION_UNAUTHORIZED",
                "ENERGY_ACTIVITY_UNAUTHORIZED");
        return response;
    }

    /** 处理方法调用期间的角色拒绝；安全过滤链中的拒绝由 AccessDeniedHandler 返回同结构 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> handleAccessDeniedException(
            AccessDeniedException e, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 403);
        response.put("msg", "无权限访问");
        response.put("success", false);
        addTraceId(response, request);
        securityAuditService.recordDenied(request, currentUserId(), "ROLE_DENIED");
        addVersionedErrorCode(response, request,
                "ONBOARDING_FORBIDDEN", "ASSET_FORBIDDEN",
                "COLLECTION_CONFIG_FORBIDDEN", "QUALITY_POLICY_FORBIDDEN",
                "RELATION_FORBIDDEN", "ENERGY_METADATA_FORBIDDEN",
                "ENERGY_CATALOG_FORBIDDEN", "ENERGY_CONVERSION_FORBIDDEN",
                "ENERGY_ACTIVITY_FORBIDDEN");
        return response;
    }

    /** 仅为已版本化管理 API 增加各自机器码，保持旧接口响应契约不变。 */
    private static void addVersionedErrorCode(
            Map<String, Object> response, HttpServletRequest request,
            String onboardingErrorCode, String assetErrorCode,
            String collectionErrorCode, String qualityUsageErrorCode,
            String relationErrorCode, String energyMetadataErrorCode,
            String energyCatalogErrorCode, String energyConversionErrorCode,
            String energyActivityErrorCode) {
        String path = request.getRequestURI();
        if (path.startsWith(request.getContextPath() + "/v1/device-products")
                || path.startsWith(request.getContextPath() + "/v1/device-onboarding")) {
            response.put("errorCode", onboardingErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/assets")) {
            response.put("errorCode", assetErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/data-sources")
                || path.startsWith(request.getContextPath() + "/v1/collection-")) {
            response.put("errorCode", collectionErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/quality-usage")) {
            response.put("errorCode", qualityUsageErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/relation-models")) {
            response.put("errorCode", relationErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-point-profiles")) {
            response.put("errorCode", energyMetadataErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-catalog")) {
            response.put("errorCode", energyCatalogErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-conversion")) {
            response.put("errorCode", energyConversionErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/energy-activity-data")) {
            response.put("errorCode", energyActivityErrorCode);
        } else if (path.startsWith(request.getContextPath() + "/v1/backoffice")) {
            response.put("errorCode", "BACKOFFICE_REQUEST_CONFLICT");
        }
    }

    /**
     * 请求没有匹配到 Controller 或静态资源时返回 404。
     *
     * <p>Spring MVC 6 会把未匹配路径包装成该异常；若直接落入通用异常兜底，
     * 已废弃的接口路径会被误报为服务器内部故障。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNoResourceFoundException(
            NoResourceFoundException e, HttpServletRequest request) {
        log.info("请求路径不存在: method={}, path={}", e.getHttpMethod(), e.getResourcePath());
        Map<String, Object> response = new HashMap<>();
        response.put("code", 404);
        response.put("msg", "请求路径不存在");
        response.put("success", false);
        addTraceId(response, request);
        return response;
    }

    /**
     * 兜底处理未分类异常并返回脱敏 500。
     * 完整堆栈只写服务端日志，前端不会看到 SQL、连接信息或实现类名。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleAllException(Exception e, HttpServletRequest request) {
        log.error("系统发生未知致命异常，已拦截兜底: ", e);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 500);
        // 不暴露 SQL 或代码异常给前端，统一回复系统繁忙
        response.put("msg", "系统当前线路繁忙，请稍后再试");
        response.put("success", false);
        addTraceId(response, request);
        return response;
    }

    private static void addTraceId(Map<String, Object> response, HttpServletRequest request) {
        response.put("traceId", TraceContext.from(request));
    }

    private static Long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof JwtUserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }
}
