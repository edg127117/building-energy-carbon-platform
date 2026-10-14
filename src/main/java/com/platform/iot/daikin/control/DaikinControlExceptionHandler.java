package com.platform.iot.daikin.control;

import com.platform.audit.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 控制请求解码失败返回稳定400，不回显含未知字段的原始请求。 */
@Order(-1)
@RestControllerAdvice(assignableTypes = {DaikinControlController.class, DaikinControlGrantController.class, DaikinControlRecoveryController.class})
public class DaikinControlExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalid(HttpServletRequest request) {
        return Map.of("code", 400, "success", false, "msg", "控制参数无效", "errorCode", "HVAC_CONTROL_INVALID_REQUEST",
                "traceId", TraceContext.from(request));
    }
}
