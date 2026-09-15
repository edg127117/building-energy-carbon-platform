package com.platform.adapter.parser;

/** 无法通过 MQTT 重投修复的协议或载荷错误。 */
public class TelemetryAdaptationException extends RuntimeException {

    private final String code;

    public TelemetryAdaptationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TelemetryAdaptationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
