package com.platform.iot.ingest;

import com.platform.iot.dataquality.event.HvacLateRealEventStoredEvent;
import com.platform.iot.quality.*;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HvacIngestionServiceTest {

    private static final long EVENT_TIME = 1_784_788_220_000L;

    @Mock private TelemetryQualityValidator validator;
    @Mock private HvacRawEventRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private HvacIngestionService service;
    private Map<String, Object> payload;

    @BeforeEach
    void setUp() {
        service = new HvacIngestionService(
                validator, repository, new SimpleMeterRegistry(), eventPublisher,
                30, "MQTT_FREEZE_V1");
        payload = Map.of("pointCode", "WCR1_TWin");
    }

    @Test
    void acceptedEventIsPersistedWithPlatformQualityAndOnTimeFlag() {
        long receivedTime = EVENT_TIME + 1_000L;
        when(validator.validate(payload, receivedTime, "MQTT_FREEZE_V1"))
                .thenReturn(accepted(receivedTime));
        when(repository.upsert(any())).thenReturn(RawEventWriteResult.INSERTED);

        HvacIngestionResult result = service.ingest(payload, receivedTime);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.ACCEPTED);
        assertThat(result.shouldAcknowledge()).isTrue();
        verify(repository).upsert(argThat(event ->
                event.dataQuality() == 0 && !event.late() && event.eventTime() == EVENT_TIME));
    }

    @Test
    void trustedSourceOverloadUsesOnlyServerSelectedNamespace() {
        when(validator.validate(payload, EVENT_TIME, "MQTT_STANDARD_V1"))
                .thenReturn(TelemetryValidationResult.reject(
                        TelemetryRejectionReason.POINT_NOT_FOUND, "测点未配置"));

        service.ingest(payload, EVENT_TIME, "MQTT_STANDARD_V1");

        verify(validator).validate(payload, EVENT_TIME, "MQTT_STANDARD_V1");
        verify(validator, never()).validate(payload, EVENT_TIME, "MQTT_FREEZE_V1");
    }

    @Test
    void eventReceivedAfterWindowCutoffIsStoredAsLate() {
        long minuteStart = EVENT_TIME - Math.floorMod(EVENT_TIME, 60_000L);
        long receivedTime = minuteStart + 90_001L;
        when(validator.validate(payload, receivedTime, "MQTT_FREEZE_V1"))
                .thenReturn(accepted(receivedTime));
        when(repository.upsert(any())).thenReturn(RawEventWriteResult.INSERTED);

        service.ingest(payload, receivedTime);

        verify(repository).upsert(argThat(RawTelemetryEvent::late));
        verify(eventPublisher).publishEvent(new HvacLateRealEventStoredEvent(
                "POINT001", "BLD001", minuteStart, receivedTime));
    }

    @Test
    void rejectedEventNeverTouchesTdengine() {
        when(validator.validate(payload, EVENT_TIME, "MQTT_FREEZE_V1")).thenReturn(
                TelemetryValidationResult.reject(
                        TelemetryRejectionReason.POINT_NOT_FOUND, "测点未配置"));

        HvacIngestionResult result = service.ingest(payload, EVENT_TIME);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo(TelemetryRejectionReason.POINT_NOT_FOUND);
        assertThat(result.shouldAcknowledge()).isTrue();
        verifyNoInteractions(repository);
    }

    @Test
    void mapsDuplicateAndConflictRepositoryOutcomes() {
        when(validator.validate(payload, EVENT_TIME, "MQTT_FREEZE_V1"))
                .thenReturn(accepted(EVENT_TIME));
        when(repository.upsert(any()))
                .thenReturn(RawEventWriteResult.DUPLICATE)
                .thenReturn(RawEventWriteResult.CONFLICT_UPDATED);

        assertThat(service.ingest(payload, EVENT_TIME).outcome())
                .isEqualTo(IngestionOutcome.DUPLICATE);
        assertThat(service.ingest(payload, EVENT_TIME).outcome())
                .isEqualTo(IngestionOutcome.CONFLICT_UPDATED);
    }

    @Test
    void duplicateLateEventDoesNotPublishAnotherCorrection() {
        long minuteStart = EVENT_TIME - Math.floorMod(EVENT_TIME, 60_000L);
        long receivedTime = minuteStart + 90_001L;
        when(validator.validate(payload, receivedTime, "MQTT_FREEZE_V1"))
                .thenReturn(accepted(receivedTime));
        when(repository.upsert(any())).thenReturn(RawEventWriteResult.DUPLICATE);

        service.ingest(payload, receivedTime);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void conflictUpdatedLateEventPublishesCorrection() {
        long minuteStart = EVENT_TIME - Math.floorMod(EVENT_TIME, 60_000L);
        long receivedTime = minuteStart + 90_001L;
        when(validator.validate(payload, receivedTime, "MQTT_FREEZE_V1"))
                .thenReturn(accepted(receivedTime));
        when(repository.upsert(any()))
                .thenReturn(RawEventWriteResult.CONFLICT_UPDATED);

        service.ingest(payload, receivedTime);

        verify(eventPublisher).publishEvent(new HvacLateRealEventStoredEvent(
                "POINT001", "BLD001", minuteStart, receivedTime));
    }

    @Test
    void lateDispatchFailureDoesNotMisreportSuccessfulRawStorage() {
        long minuteStart = EVENT_TIME - Math.floorMod(EVENT_TIME, 60_000L);
        long receivedTime = minuteStart + 90_001L;
        when(validator.validate(payload, receivedTime, "MQTT_FREEZE_V1"))
                .thenReturn(accepted(receivedTime));
        when(repository.upsert(any()))
                .thenReturn(RawEventWriteResult.INSERTED);
        doThrow(new IllegalStateException("event bus unavailable"))
                .when(eventPublisher).publishEvent(any(Object.class));

        HvacIngestionResult result = service.ingest(payload, receivedTime);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.ACCEPTED);
        assertThat(result.shouldAcknowledge()).isTrue();
        verify(repository, times(1)).upsert(any());
    }

    @Test
    void retriesStorageThreeTimesAndDoesNotAcknowledgeFinalFailure() {
        when(validator.validate(payload, EVENT_TIME, "MQTT_FREEZE_V1"))
                .thenReturn(accepted(EVENT_TIME));
        when(repository.upsert(any())).thenThrow(new IllegalStateException("tdengine unavailable"));

        HvacIngestionResult result = service.ingest(payload, EVENT_TIME);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.STORAGE_FAILED);
        assertThat(result.shouldAcknowledge()).isFalse();
        verify(repository, times(3)).upsert(any());
        verifyNoInteractions(eventPublisher);
    }

    private TelemetryValidationResult accepted(long receivedTime) {
        return TelemetryValidationResult.accept(new ValidatedHvacTelemetry(
                "POINT001", "WCR1_TWin",
                "MQTT_FREEZE_V1", "WCR1_TWin", "WCR1",
                "BLD001", "GROUP001", "EQUIP001", "WCR1",
                "WCR", "MAIN", "TWin",
                12.3, EVENT_TIME, receivedTime, 0, 1));
    }
}
