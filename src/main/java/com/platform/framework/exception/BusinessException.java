package com.platform.framework.exception;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 自定义业务逻辑异常
 * 作用：用于在 Service 层主动阻断程序并抛出业务错误（如：设备不存在、密码错误等）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BusinessException extends RuntimeException {

    /**
     * 业务错误码 (前端可以根据不同的 code 做出不同的响应，比如 401 跳转登录页)
     */
    private Integer code;

    /**
     * 只传入错误信息的构造方法 (默认错误码 400)
     */
    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    /**
     * 传入自定义错误码和信息的构造方法
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}