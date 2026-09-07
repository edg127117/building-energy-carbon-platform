package com.platform.modbus.mqtt;

/** 携带稳定 MQTT 失败分类，使上层重试和监控不依赖底层异常文本。 */
public class TelemetryPublishException extends RuntimeException {

    private final MqttFailureCategory failureCategory;

    public TelemetryPublishException(String message, Throwable cause) {
        this(MqttFailureCategory.UNKNOWN, message, cause);
    }

    public TelemetryPublishException(
            MqttFailureCategory failureCategory, String message, Throwable cause) {
        super(message, cause);
        this.failureCategory = failureCategory;
    }

    public MqttFailureCategory failureCategory() {
        return failureCategory;
    }
}
