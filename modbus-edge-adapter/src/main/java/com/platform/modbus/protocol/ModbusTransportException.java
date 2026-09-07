package com.platform.modbus.protocol;

/** 将连接、超时和协议错误归为稳定类别，供有限重试与失败指标使用。 */
public class ModbusTransportException extends RuntimeException {

    private final FailureType failureType;

    public ModbusTransportException(FailureType failureType, String message, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    public FailureType failureType() {
        return failureType;
    }

    public enum FailureType {
        CONNECTION,
        TIMEOUT,
        PROTOCOL,
        CONFIGURATION,
        UNKNOWN
    }
}
