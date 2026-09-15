package com.platform.iot.daikin.client;

/**
 * 对外只暴露稳定错误分类；不携带厂家响应、请求、Token 或底层异常文本。
 */
public final class DaikinClientException extends RuntimeException {
    private final Code code;

    public DaikinClientException(Code code) {
        super(code.message);
        this.code = code;
    }

    public DaikinClientException(Code code, Throwable cause) {
        // 外部库异常可能携带 URL、Header 或响应片段；边界异常只保留稳定分类，不传播原始上下文。
        super(code.message, null, false, false);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        CLIENT_DISABLED("大金只读客户端未启用"),
        WIRE_NOT_CONFIRMED("大金 wire 封装尚未确认"),
        INVALID_REQUEST("大金请求参数无效"),
        INSECURE_TARGET("大金目标不是安全 HTTPS 地址"),
        CONCURRENCY_LIMIT("大金请求并发已达上限"),
        INTERRUPTED("大金请求已中断"),
        TRANSPORT_FAILURE("大金请求失败"),
        RESPONSE_TOO_LARGE("大金响应超过大小限制"),
        INVALID_RESPONSE("大金响应无法解析"),
        REMOTE_REJECTED("大金接口拒绝请求"),
        AUTHENTICATION_REQUIRED("大金认证已失效"),
        INVALID_TOKEN_RESPONSE("大金 Token 响应无效"),
        CRYPTO_FAILURE("大金协议加密失败");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }
}
