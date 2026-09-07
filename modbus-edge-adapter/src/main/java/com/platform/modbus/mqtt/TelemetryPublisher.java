package com.platform.modbus.mqtt;

import com.platform.modbus.model.StandardTelemetryMessage;

public interface TelemetryPublisher {

    void publish(StandardTelemetryMessage message);
}
