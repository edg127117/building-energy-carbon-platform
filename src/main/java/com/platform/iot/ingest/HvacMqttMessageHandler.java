package com.platform.iot.ingest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MQTT 协议层到 HVAC 接入服务的轻量适配器。
 *
 * <p>它让庞大的 MQTT 连接配置不再承担测点质量和 TDengine 业务。</p>
 */
@Component
@RequiredArgsConstructor
public class HvacMqttMessageHandler {

    private final HvacIngestionService ingestionService;

    public HvacIngestionResult handle(Map<String, Object> payload, long receivedTime) {
        return ingestionService.ingest(payload, receivedTime);
    }
}
