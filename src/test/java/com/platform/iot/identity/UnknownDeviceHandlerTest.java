package com.platform.iot.identity;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownDeviceHandlerTest {

    @Test
    void recordsBoundedMetricWithoutUsingIdentityValueAsTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UnknownDeviceHandler handler = new UnknownDeviceHandler(registry);

        handler.recordRejected(
                new DeviceIdentityKey("MAC", "123456789012345"),
                "ENERGY_METER_V1");

        assertThat(registry.get("iot.telemetry.unknown-device")
                .tags("identity.type", "MAC", "profile.code", "ENERGY_METER_V1")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters())
                .noneMatch(meter -> meter.getId().getTags().stream()
                        .anyMatch(tag -> "123456789012345".equals(tag.getValue())));
    }
}
