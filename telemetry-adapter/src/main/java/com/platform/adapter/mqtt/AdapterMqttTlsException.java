package com.platform.adapter.mqtt;

/** 适配器 TLS 配置或密钥材料无法安全加载。 */
public class AdapterMqttTlsException extends RuntimeException {
    public AdapterMqttTlsException(String message) {
        super(message);
    }

    public AdapterMqttTlsException(String message, Throwable cause) {
        super(message, cause);
    }
}
