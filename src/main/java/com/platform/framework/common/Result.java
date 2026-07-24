package com.platform.framework.common;

import lombok.Data;

/**
 * 全局统一 API 响应格式 (系统的第一道防线)
 * 无论后端发生什么，都必须以这个格式返回给前端。前端只要认准 code == 200 就是成功，否则直接弹窗显示 msg。
 */
@Data
public class Result<T> {
    private Integer code;
    private String msg;
    private T data;
    private boolean success;

    // 成功响应
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

    // 失败响应 (用于全局异常拦截器调用)
    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(null);
        result.setSuccess(false);
        return result;
    }
}
