package com.platform.modbus.mqtt;

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
