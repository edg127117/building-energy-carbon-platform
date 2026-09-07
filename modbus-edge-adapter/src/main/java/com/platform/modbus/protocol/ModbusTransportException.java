package com.platform.modbus.protocol;

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
