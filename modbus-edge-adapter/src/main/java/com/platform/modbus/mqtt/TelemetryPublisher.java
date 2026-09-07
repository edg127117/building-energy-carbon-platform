package com.platform.modbus.mqtt;

import com.platform.modbus.model.StandardTelemetryMessage;

/** 发布完整 V2 采样批次；Broker PUBACK 与平台持久化应用 ACK 由实现分别处理。 */
public interface TelemetryPublisher {

    void publish(StandardTelemetryMessage message);
}
