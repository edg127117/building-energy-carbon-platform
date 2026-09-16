package com.platform.iot.daikin.monitoring;

import com.platform.iot.daikin.model.*;
import com.platform.iot.ingest.*;
import com.platform.iot.quality.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaikinTemperatureIngestionTest {
    private final DataPointConfigProvider points = mock(DataPointConfigProvider.class);
    private final HvacIngestionService ingestion = mock(HvacIngestionService.class);
    private final DaikinTemperatureIngestion service = new DaikinTemperatureIngestion(points, ingestion);
    private final DaikinMonitoringTargets.Target target = new DaikinMonitoringTargets.Target(
            new DaikinDeviceKey("s", "site", "c", DaikinDeviceKey.Kind.INDOOR, "u"), "p", "i", "identity",
            "DAIKIN_INDOOR_V2", "e", "EC", "b", null, null, 1);

    @Test
    void confirmedBoundTemperatureUsesOriginalTimestampAndImmutableIngestion() {
        when(points.find(any())).thenReturn(Optional.of(point()));
        when(ingestion.ingestImmutable(anyMap(), anyLong(), eq("DAIKIN_V2"))).thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED));
        var observation = observation(DaikinFieldValue.Status.PRESENT);
        assertThat(service.persist(target, observation).fields().get("roomTemp").status()).isEqualTo(DaikinFieldValue.Status.PRESENT);
        verify(ingestion).ingestImmutable(argThat(payload -> new BigDecimal("23.5").equals(payload.get("val"))
                && payload.get("timestamp").equals(observation.observedAt().toEpochMilli())), eq(observation.observedAt().toEpochMilli()), eq("DAIKIN_V2"));
    }

    @Test
    void unconfirmedOrUnboundFieldCannotBecomeFormalNumericReading() {
        when(points.find(any())).thenReturn(Optional.empty());
        assertThat(service.persist(target, observation(DaikinFieldValue.Status.PRESENT)).fields().get("roomTemp").status()).isEqualTo(DaikinFieldValue.Status.UNCONFIRMED);
        service.persist(target, observation(DaikinFieldValue.Status.UNCONFIRMED));
        verifyNoInteractions(ingestion);
    }

    @Test
    void storageFailureMustRemainRetryableButRejectedValueDoesNotRefreshState() {
        when(points.find(any())).thenReturn(Optional.of(point()));
        when(ingestion.ingestImmutable(anyMap(), anyLong(), anyString())).thenReturn(HvacIngestionResult.of(IngestionOutcome.STORAGE_FAILED));
        assertThatThrownBy(() -> service.persist(target, observation(DaikinFieldValue.Status.PRESENT))).hasMessage("DAIKIN_TEMPERATURE_STORAGE_FAILED");
        when(ingestion.ingestImmutable(anyMap(), anyLong(), anyString())).thenReturn(HvacIngestionResult.of(IngestionOutcome.REJECTED));
        assertThat(service.persist(target, observation(DaikinFieldValue.Status.PRESENT)).fields().get("roomTemp").status()).isEqualTo(DaikinFieldValue.Status.INVALID);
    }

    private PointRuntimeConfig point() { return new PointRuntimeConfig("point", "PC", "温度", "b", null, "e", "EC", "AHU", "MAIN", "T", "AI", "°C", "ONLINE", 0, null, null); }
    private DaikinDeviceObservation observation(DaikinFieldValue.Status status) {
        return new DaikinDeviceObservation(target.key(), null, null, null, Instant.parse("2026-09-16T00:00:00Z"), null,
                Map.of("roomTemp", new DaikinFieldValue(status, "23.5", status == DaikinFieldValue.Status.PRESENT ? "23.5" : null)));
    }
}
