package com.platform.iot.ingest.v2;

import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.CorrelationPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AckModeResolverTest {

    private final AckModeResolver resolver = new AckModeResolver();

    @Test
    void selectsDirectOnlyWithTrustedRouteAndRequiredCorrelation() {
        AckModeResolution result = resolver.resolve(
                binding(AckMode.DEVICE_DIRECT, CorrelationPolicy.SOURCE_MESSAGE_ID,
                        "device/ack/known", "adapter/ack"),
                message("source-1", "DEVICE_DIRECT"));

        assertThat(result.actualMode()).isEqualTo(AckMode.DEVICE_DIRECT);
        assertThat(result.ackTopic()).isEqualTo("device/ack/known");
    }

    @Test
    void downgradesToEvidenceWhenCorrelationFieldIsMissing() {
        AckModeResolution result = resolver.resolve(
                binding(AckMode.DEVICE_DIRECT, CorrelationPolicy.SOURCE_MESSAGE_ID,
                        "device/ack/known", "adapter/ack"),
                message(null, "DEVICE_DIRECT"));

        assertThat(result.actualMode()).isEqualTo(AckMode.EVIDENCE_ONLY);
        assertThat(result.downgradeReason()).isEqualTo("CORRELATION_FIELDS_MISSING");
    }

    @Test
    void usesAdapterProxyWhenDeviceRouteIsUnavailable() {
        AckModeResolution result = resolver.resolve(
                binding(AckMode.DEVICE_DIRECT, CorrelationPolicy.SOURCE_MESSAGE_ID,
                        null, "adapter/ack"),
                message("source-1", "DEVICE_DIRECT"));

        assertThat(result.actualMode()).isEqualTo(AckMode.ADAPTER_PROXY);
        assertThat(result.downgradeReason()).isEqualTo("DEVICE_ACK_ROUTE_MISSING");
    }

    private DeviceIdentityBinding binding(
            AckMode mode,
            CorrelationPolicy policy,
            String deviceTopic,
            String adapterTopic) {
        return new DeviceIdentityBinding("I1", new DeviceIdentityKey("MAC", "ABC"),
                "E1", "METER1", "B1", "P1", mode, policy,
                deviceTopic, adapterTopic);
    }

    private TelemetryV2Message message(String sourceMessageId, String declaredMode) {
        return new TelemetryV2Message("2.0", "P1", 1,
                new DeviceIdentityKey("MAC", "ABC"), "C1", sourceMessageId,
                null, null, null, 1_785_398_400_000L, null, null,
                null, null, sourceMessageId == null ? "ADAPTER_GENERATED" : "DEVICE_REPORTED",
                "ADAPTER_RECEIVED", sourceMessageId == null ? "NONE" : "EXACT",
                declaredMode, sourceMessageId == null ? "NONE" : "SOURCE_MESSAGE_ID",
                List.of(new StandardMetric("P", BigDecimal.ONE, "kWh", "/p")));
    }
}
