package com.platform.energy.efficiency;

import com.platform.audit.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 仅将冷站接口的非法JSON/枚举/参数绑定转换为400，保留公共业务异常和安全审计链。 */
@RestControllerAdvice(assignableTypes=EerpController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EerpInputAdvice {
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public EerpApiError invalidInput(HttpServletRequest request) {
        return new EerpApiError(400,"EERP_INVALID_REQUEST","请求JSON、枚举或参数格式无效",false,TraceContext.from(request));
    }
}
