package com.platform.framework.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 1. 拦截参数校验异常 (比如输入了超长字符串、乱码)
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidationException(Exception e) {
        log.warn("前端非法输入拦截: {}", e.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("code", 400);
        response.put("msg", "您输入的格式不正确，请检查后重试");
        response.put("success", false);
        return response; // 永远返回 JSON
    }
    /**
     * 2. 拦截自定义异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        log.info("业务逻辑阻断: {}", e.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("code", e.getCode());
        response.put("msg", e.getMessage());
        response.put("success", false);
        HttpStatus status = switch (e.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, Object> handleAuthenticationException(AuthenticationException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 401);
        response.put("msg", "未登录或登录已过期");
        response.put("success", false);
        return response;
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> handleAccessDeniedException(AccessDeniedException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 403);
        response.put("msg", "无权限访问");
        response.put("success", false);
        return response;
    }
    /**
     * 3. 拦截所有未知的致命报错 (NullPointer, 数据库宕机等)
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleAllException(Exception e) {
        log.error("系统发生未知致命异常，已拦截兜底: ", e);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 500);
        // 不暴露 SQL 或代码异常给前端，统一回复系统繁忙
        response.put("msg", "系统当前线路繁忙，请稍后再试");
        response.put("success", false);
        return response;
    }
}
