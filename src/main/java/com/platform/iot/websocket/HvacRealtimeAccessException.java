package com.platform.iot.websocket;

/**
 * 实时协议可安全返回给浏览器的拒绝结果。
 *
 * <p>端点只使用稳定错误码、关闭码和脱敏文案构造 {@code ERROR} 帧；JWT、权限服务异常和
 * 数据访问细节不应通过该异常或日志回传给客户端。</p>
 */
public final class HvacRealtimeAccessException extends RuntimeException {

    private final String errorCode;
    private final int closeCode;
    private final String publicMessage;

    public HvacRealtimeAccessException(
            String errorCode, int closeCode, String publicMessage) {
        super(publicMessage);
        this.errorCode = errorCode;
        this.closeCode = closeCode;
        this.publicMessage = publicMessage;
    }

    public String errorCode() {
        return errorCode;
    }

    public int closeCode() {
        return closeCode;
    }

    public String publicMessage() {
        return publicMessage;
    }
}
