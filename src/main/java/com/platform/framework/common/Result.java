package com.platform.framework.common;

import lombok.Data;

/**
 * 业务 Controller 的统一 JSON 响应体。
 *
 * <p>成功响应使用 {@code code=200, success=true}；业务失败通常由异常处理器返回相同的
 * {@code code/msg/success} 结构并同步设置 HTTP 状态。Spring Security 的 401/403 入口也保持
 * 这些字段，便于前端统一识别，但并非所有框架级响应都由本类实例生成。</p>
 */
@Data
public class Result<T> {
    private Integer code;
    private String msg;
    private T data;
    private boolean success;

    /** 包装带数据的成功结果，供 Controller 返回给调用方。 */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg("操作成功");
        result.setData(data);
        result.setSuccess(true);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    /** 包装不抛异常的显式失败结果；HTTP 状态是否变化由调用该方法的 Controller 决定。 */
    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(null);
        result.setSuccess(false);
        return result;
    }
}
