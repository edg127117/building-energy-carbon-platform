package com.platform.iot.protocol.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 样例可能含设备敏感字段，输入错误不得记录包含拒绝值的 Bean Validation 异常文本。 */
@RestControllerAdvice(assignableTypes=ProtocolConfigurationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProtocolInputExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> invalidInput() {
        return ResponseEntity.badRequest().body(Map.of("code",400,"success",false,
                "errorCode","PROTOCOL_VALIDATION_FAILED","msg","配置或样例格式无效，请检查必填项和输入限制"));
    }
}
