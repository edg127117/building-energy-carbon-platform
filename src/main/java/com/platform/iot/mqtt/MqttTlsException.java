package com.platform.iot.mqtt;

/** TLS 部署参数或密钥材料无法安全加载时抛出的启动期异常。 */
public class MqttTlsException extends RuntimeException {
    public MqttTlsException(String message, Throwable cause) {
        super(message, cause);
    }

    public MqttTlsException(String message) {
        super(message);
    }
}
