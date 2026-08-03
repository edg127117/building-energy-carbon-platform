package com.platform.framework.exception;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Service 主动终止业务流程时携带给 HTTP 层的可公开错误。
 *
 * <p>{@link GlobalExceptionHandler} 把 {@code code} 映射为对应 HTTP 状态，并把消息放入统一
 * JSON 响应。调用方只能放置经过脱敏、适合前端展示的文案，不能包含 SQL、密码、Token 或堆栈。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BusinessException extends RuntimeException {

    /** 与 HTTP 语义对应的业务码，例如 401、403、404、409 或 503。 */
    private Integer code;

    /** 创建默认 400 的请求/业务规则错误。 */
    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    /** 创建带明确状态语义的业务错误，由全局异常处理器完成 HTTP 映射。 */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
