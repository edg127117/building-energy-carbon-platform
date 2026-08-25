package com.platform.iot.ingest.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.ReceiptStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
/** V2 内部 Topic 的反序列化边界和投递证据回写入口。 */
public class TelemetryV2MqttMessageHandler {

    private final ObjectMapper objectMapper;
    private final TelemetryV2IngestionService ingestionService;

    public TelemetryV2MqttMessageHandler(
            ObjectMapper objectMapper,
            TelemetryV2IngestionService ingestionService) {
        this.objectMapper = objectMapper;
        this.ingestionService = ingestionService;
    }

    public V2ProcessingResult handle(byte[] payload, long platformReceivedAt) {
        if (payload == null || payload.length == 0) {
            return malformedWithEvidence("V2 MQTT 报文为空");
        }
        try {
            return ingestionService.ingest(
                    objectMapper.readValue(payload, TelemetryV2Message.class),
                    platformReceivedAt);
        } catch (IOException | IllegalArgumentException exception) {
            return malformedWithEvidence("V2 MQTT 报文 JSON 无效");
        } catch (RuntimeException exception) {
            return new V2ProcessingResult(null, null, "V2_TEMPORARY_FAILURE", 0,
                    true, AckMode.EVIDENCE_ONLY, null, null, exception.getMessage());
        }
    }

    public void markDeliveryCompleted(String canonicalMessageId, boolean applicationAckPublished) {
        ingestionService.markDeliveryCompleted(canonicalMessageId, applicationAckPublished);
    }

    public void recordApplicationAckFailure(String canonicalMessageId, String detail) {
        ingestionService.recordApplicationAckFailure(canonicalMessageId, detail);
    }

    private V2ProcessingResult malformedWithEvidence(String detail) {
        ingestionService.recordMalformedFailure(detail);
        return malformed(detail);
    }

    private V2ProcessingResult malformed(String detail) {
        return new V2ProcessingResult(null, ReceiptStatus.PLATFORM_REJECTED,
                "MALFORMED_V2_JSON", 0, false, AckMode.EVIDENCE_ONLY,
                null, null, detail);
    }
}
