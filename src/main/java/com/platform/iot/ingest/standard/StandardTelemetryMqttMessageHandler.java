package com.platform.iot.ingest.standard;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
/** 标准 MQTT Topic 的 JSON 反序列化边界，不承担设备归属和时序存储规则。 */
public class StandardTelemetryMqttMessageHandler {

    private final ObjectMapper objectMapper;
    private final StandardTelemetryIngestionService ingestionService;

    /** 将云端标准 JSON 交给本地批次接入服务，格式错误按不可修复毒消息拒绝。 */
    public StandardTelemetryResult handle(byte[] payload, long localReceivedTime) {
        if (payload == null || payload.length == 0) {
            return StandardTelemetryResult.rejected("标准MQTT报文为空");
        }
        try {
            StandardTelemetryMessage message = objectMapper.readValue(
                    payload, StandardTelemetryMessage.class);
            return ingestionService.ingest(message, localReceivedTime);
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("标准MQTT报文JSON格式错误，已拒绝: reason={}", exception.getMessage());
            return StandardTelemetryResult.rejected("标准MQTT报文JSON格式错误");
        } catch (RuntimeException exception) {
            log.warn("标准MQTT报文处理暂时失败，保留重投: reason={}", exception.getMessage());
            return StandardTelemetryResult.retryable(0, exception.getMessage());
        }
    }
}
