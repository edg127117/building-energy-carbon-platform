package com.platform.adapter.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.model.DeviceIdentity;
import com.platform.adapter.model.StandardMetric;
import com.platform.adapter.model.StandardTelemetryMessage;
import com.platform.adapter.model.TimeSource;
import com.platform.adapter.profile.AdapterProfileProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按适配器声明能力序列化平台 V1 或 V2 标准 MQTT 报文。 */
@Component
public class TelemetryOutputSerializer {

    private final ObjectMapper objectMapper;
    private final AdapterProfileProperties properties;

    public TelemetryOutputSerializer(
            ObjectMapper objectMapper, AdapterProfileProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public byte[] serialize(StandardTelemetryMessage message) throws JsonProcessingException {
        if ("V2".equalsIgnoreCase(properties.getOutputVersion())) {
            return objectMapper.writeValueAsBytes(message);
        }
        if (!"V1".equalsIgnoreCase(properties.getOutputVersion())) {
            throw new IllegalArgumentException("adapter.profile.output-version仅支持V1或V2");
        }
        long eventTime = message.collectedAt() == null
                ? message.adapterReceivedAt() : message.collectedAt();
        String timeSource = message.timeSource() == TimeSource.DEVICE_REPORTED
                ? "DEVICE_REPORTED" : "SERVER_RECEIVED";
        return objectMapper.writeValueAsBytes(new V1Message(
                "1.0", message.profileCode(), message.profileVersion(),
                message.deviceIdentity(), eventTime, message.adapterReceivedAt(),
                timeSource, message.sourceSeq(), message.metrics()));
    }

    public boolean supportsProxyAck() {
        return "V2".equalsIgnoreCase(properties.getOutputVersion());
    }

    private record V1Message(
            String standardVersion,
            String profileCode,
            int profileVersion,
            DeviceIdentity deviceIdentity,
            long eventTime,
            long receivedTime,
            String timeSource,
            Long seq,
            List<StandardMetric> metrics) {
    }
}
