package com.platform.iot.onboarding;

import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingDeviceDiscoveryServiceTest {

    @Test
    void retriesTransientWriteAndKeepsOneStableDiscoveryCandidate() {
        PendingDeviceRepository repository = mock(PendingDeviceRepository.class);
        PendingDeviceSampleEncoder encoder = mock(PendingDeviceSampleEncoder.class);
        PendingDeviceDiscoveryProperties properties = properties();
        when(encoder.encode(message())).thenReturn(
                new PendingDeviceSample("{\"metrics\":[]}", true));
        doThrow(new IllegalStateException("temporary"))
                .doThrow(new IllegalStateException("temporary"))
                .doNothing()
                .when(repository).upsertDiscovery(
                        org.mockito.ArgumentMatchers.any());
        PendingDeviceDiscoveryService service = new PendingDeviceDiscoveryService(
                repository, encoder, properties);

        boolean truncated = service.discover(message(), 1_785_398_401_000L);

        assertThat(truncated).isTrue();
        ArgumentCaptor<PendingDeviceDiscovery> captor =
                ArgumentCaptor.forClass(PendingDeviceDiscovery.class);
        verify(repository, times(3)).upsertDiscovery(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(PendingDeviceDiscovery::pendingId)
                .containsOnly(captor.getValue().pendingId());
        assertThat(captor.getValue().identity().value())
                .isEqualTo("123456789012345");
        assertThat(captor.getValue().metricsJson())
                .isEqualTo("{\"metrics\":[]}");
    }

    @Test
    void rethrowsAfterConfiguredAttemptsSoBoundaryCanAckAndCountFailure() {
        PendingDeviceRepository repository = mock(PendingDeviceRepository.class);
        PendingDeviceSampleEncoder encoder = mock(PendingDeviceSampleEncoder.class);
        when(encoder.encode(message())).thenReturn(
                new PendingDeviceSample("{\"metrics\":[]}", false));
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(repository).upsertDiscovery(
                        org.mockito.ArgumentMatchers.any());
        PendingDeviceDiscoveryService service = new PendingDeviceDiscoveryService(
                repository, encoder, properties());

        assertThatThrownBy(() -> service.discover(message(), 1_785_398_401_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql unavailable");
        verify(repository, times(3)).upsertDiscovery(
                org.mockito.ArgumentMatchers.any());
    }

    private PendingDeviceDiscoveryProperties properties() {
        PendingDeviceDiscoveryProperties properties = new PendingDeviceDiscoveryProperties();
        properties.setMaxAttempts(3);
        properties.setRetryDelayMs(1L);
        return properties;
    }

    private StandardTelemetryMessage message() {
        return new StandardTelemetryMessage(
                "1.0", "ENERGY_METER_V1", 2,
                new DeviceIdentityKey("MAC", "123456789012345"),
                1_785_398_400_000L,
                1_785_398_400_100L,
                "DEVICE_REPORTED",
                1L,
                List.of(new StandardMetric(
                        "CURRENT_ENERGY", new BigDecimal("12.3"),
                        "kWh", "/source")));
    }
}
