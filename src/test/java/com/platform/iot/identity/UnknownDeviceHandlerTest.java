package com.platform.iot.identity;

import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import com.platform.iot.onboarding.PendingDeviceDiscoveryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnknownDeviceHandlerTest {

    @Test
    void recordsBoundedMetricWithoutUsingIdentityValueAsTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendingDeviceDiscoveryService discoveryService =
                mock(PendingDeviceDiscoveryService.class);
        when(discoveryService.discover(message(), 1_785_398_401_000L))
                .thenReturn(true);
        UnknownDeviceHandler handler = new UnknownDeviceHandler(
                discoveryService, registry);

        handler.recordDiscovered(message(), 1_785_398_401_000L);

        assertThat(registry.get("iot.telemetry.unknown-device")
                .tags("identity.type", "MAC", "profile.code", "ENERGY_METER_V1")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("iot.device-onboarding.discovery")
                .tag("outcome", "recorded").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("iot.device-onboarding.discovery")
                .tag("outcome", "truncated").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters())
                .noneMatch(meter -> meter.getId().getTags().stream()
                        .anyMatch(tag -> "123456789012345".equals(tag.getValue())));
        verify(discoveryService).discover(message(), 1_785_398_401_000L);
    }

    @Test
    void discoveryFailureIsCountedWithoutEscapingToMqttBoundary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendingDeviceDiscoveryService discoveryService =
                mock(PendingDeviceDiscoveryService.class);
        when(discoveryService.discover(message(), 1_785_398_401_000L))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        UnknownDeviceHandler handler = new UnknownDeviceHandler(
                discoveryService, registry);

        handler.recordDiscovered(message(), 1_785_398_401_000L);

        assertThat(registry.get("iot.device-onboarding.discovery")
                .tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    private StandardTelemetryMessage message() {
        return new StandardTelemetryMessage(
                "1.0", "ENERGY_METER_V1", 1,
                new DeviceIdentityKey("MAC", "123456789012345"),
                1_785_398_400_000L,
                1_785_398_400_100L,
                "DEVICE_REPORTED",
                1L,
                List.of(new StandardMetric(
                        "CURRENT_ENERGY", new BigDecimal("12.3"),
                        "kWh", "/secret/source/path")));
    }
}
