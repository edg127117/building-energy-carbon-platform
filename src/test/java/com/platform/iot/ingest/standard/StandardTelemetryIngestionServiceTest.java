package com.platform.iot.ingest.standard;

import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.CorrelationPolicy;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.identity.DeviceIdentityProvider;
import com.platform.iot.identity.DeviceIdentitySnapshotUnavailableException;
import com.platform.iot.identity.UnknownDeviceHandler;
import com.platform.iot.ingest.HvacIngestionResult;
import com.platform.iot.ingest.HvacIngestionService;
import com.platform.iot.ingest.IngestionOutcome;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StandardTelemetryIngestionServiceTest {

    private static final long EVENT_TIME = 1_785_398_400_000L;
    private static final long LOCAL_RECEIVED_TIME = EVENT_TIME + 1_000L;

    @Mock private DeviceIdentityProvider identityProvider;
    @Mock private UnknownDeviceHandler unknownDeviceHandler;
    @Mock private DataPointConfigProvider pointProvider;
    @Mock private HvacIngestionService hvacIngestionService;

    private StandardTelemetryIngestionService service;

    @BeforeEach
    void setUp() {
        service = new StandardTelemetryIngestionService(
                identityProvider,
                unknownDeviceHandler,
                pointProvider,
                hvacIngestionService,
                "MQTT_STANDARD_V1");
    }

    @Test
    void preparesAndWritesAllMetricsWhenNoConflictOccurs() {
        when(identityProvider.find(identityKey())).thenReturn(Optional.of(binding()));
        when(pointProvider.find(any())).thenAnswer(invocation -> {
            PointAliasKey key = invocation.getArgument(0);
            return key.sourcePointCode().endsWith("CURRENT_ENERGY")
                    ? Optional.of(point("POINT_ENERGY", "kWh", "EQUIP001", "BLD001"))
                    : Optional.of(point("POINT_CO2", "kgCO2e", "EQUIP001", "BLD001"));
        });
        prepareEvents(preparedEvent("POINT_ENERGY", 12.34), preparedEvent("POINT_CO2", 7.88));
        when(hvacIngestionService.writePrepared(any(), eq(true)))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED));

        StandardTelemetryResult result = service.ingest(message(List.of(
                metric("CURRENT_ENERGY", "12.34", "kWh"),
                metric("CURRENT_CO2", "7.88", "kgCO2e"))), LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.ACCEPTED);
        assertThat(result.processedMetrics()).isEqualTo(2);
        assertThat(result.shouldAcknowledge()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hvacIngestionService, org.mockito.Mockito.times(2)).prepare(
                payloadCaptor.capture(), eq(LOCAL_RECEIVED_TIME), eq("MQTT_STANDARD_V1"));
        assertThat(payloadCaptor.getAllValues())
                .extracting(payload -> payload.get("pointCode"))
                .containsExactly(
                        "MAC:123456789012345:CURRENT_ENERGY",
                        "MAC:123456789012345:CURRENT_CO2");
        assertThat(payloadCaptor.getAllValues())
                .allSatisfy(payload -> {
                    assertThat(payload.get("buildingId")).isEqualTo("BLD001");
                    assertThat(payload.get("deviceId")).isEqualTo("METER001");
                    assertThat(payload.get("timestamp")).isEqualTo(EVENT_TIME);
                });
        verify(hvacIngestionService, org.mockito.Mockito.times(2)).writePrepared(any(), eq(true));
        verify(hvacIngestionService, never()).retainConflict(any());
    }

    @Test
    void unknownDeviceIsDiscoveredAndRejectedBeforePointLookup() {
        when(identityProvider.find(identityKey())).thenReturn(Optional.empty());

        StandardTelemetryMessage message = message(
                List.of(metric("CURRENT_ENERGY", "12.34", "kWh")));
        StandardTelemetryResult result = service.ingest(message, LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        assertThat(result.shouldAcknowledge()).isTrue();
        verify(unknownDeviceHandler).recordDiscovered(message, LOCAL_RECEIVED_TIME);
        verifyNoInteractions(pointProvider, hvacIngestionService);
    }

    @Test
    void registeredButDisabledIdentityIsRejectedWithoutPendingDiscovery() {
        when(identityProvider.find(identityKey())).thenReturn(Optional.empty());
        when(identityProvider.isKnown(identityKey())).thenReturn(true);
        StandardTelemetryMessage message = message(
                List.of(metric("CURRENT_ENERGY", "12.34", "kWh")));

        StandardTelemetryResult result = service.ingest(message, LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        assertThat(result.shouldAcknowledge()).isTrue();
        verifyNoInteractions(unknownDeviceHandler, pointProvider, hvacIngestionService);
    }

    @Test
    void unavailableIdentitySnapshotIsRetryable() {
        when(identityProvider.find(identityKey()))
                .thenThrow(new DeviceIdentitySnapshotUnavailableException("mysql unavailable"));

        StandardTelemetryResult result = service.ingest(
                message(List.of(metric("CURRENT_ENERGY", "12.34", "kWh"))),
                LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.RETRYABLE_FAILURE);
        assertThat(result.shouldAcknowledge()).isFalse();
        verifyNoInteractions(pointProvider, hvacIngestionService);
    }

    @Test
    void rejectsProfileMismatchAndDuplicateMetricBeforeAnyWrite() {
        when(identityProvider.find(identityKey())).thenReturn(Optional.of(binding()));
        StandardTelemetryMessage wrongProfile = new StandardTelemetryMessage(
                "1.0", "OTHER_PROFILE", 1, identityKey(), EVENT_TIME,
                EVENT_TIME, "DEVICE_REPORTED", 1L,
                List.of(metric("CURRENT_ENERGY", "1", "kWh")));

        assertThat(service.ingest(wrongProfile, LOCAL_RECEIVED_TIME).outcome())
                .isEqualTo(StandardTelemetryOutcome.REJECTED);

        StandardTelemetryMessage duplicate = message(List.of(
                metric("CURRENT_ENERGY", "1", "kWh"),
                metric("CURRENT_ENERGY", "2", "kWh")));
        assertThat(service.ingest(duplicate, LOCAL_RECEIVED_TIME).outcome())
                .isEqualTo(StandardTelemetryOutcome.REJECTED);
        verifyNoInteractions(pointProvider, hvacIngestionService);
    }

    @Test
    void rejectsMissingAliasOwnershipMismatchAndUnitMismatchBeforeAnyWrite() {
        when(identityProvider.find(identityKey())).thenReturn(Optional.of(binding()));
        StandardTelemetryMessage oneMetric = message(
                List.of(metric("CURRENT_ENERGY", "12.34", "kWh")));

        when(pointProvider.find(any())).thenReturn(Optional.empty());
        assertThat(service.ingest(oneMetric, LOCAL_RECEIVED_TIME).outcome())
                .isEqualTo(StandardTelemetryOutcome.REJECTED);

        when(pointProvider.find(any())).thenReturn(Optional.of(
                point("POINT_ENERGY", "kWh", "OTHER_EQUIP", "BLD001")));
        assertThat(service.ingest(oneMetric, LOCAL_RECEIVED_TIME).outcome())
                .isEqualTo(StandardTelemetryOutcome.REJECTED);

        when(pointProvider.find(any())).thenReturn(Optional.of(
                point("POINT_ENERGY", "Wh", "EQUIP001", "BLD001")));
        assertThat(service.ingest(oneMetric, LOCAL_RECEIVED_TIME).outcome())
                .isEqualTo(StandardTelemetryOutcome.REJECTED);

        verify(hvacIngestionService, never()).ingest(anyMap(), anyLong(), any());
    }

    @Test
    void storageFailureAfterEarlierSuccessKeepsWholeMqttPacketForRedelivery() {
        when(identityProvider.find(identityKey())).thenReturn(Optional.of(binding()));
        when(pointProvider.find(any())).thenReturn(Optional.of(
                point("POINT", "kWh", "EQUIP001", "BLD001")));
        prepareEvents(preparedEvent("POINT_1", 12.34), preparedEvent("POINT_2", 0.25));
        when(hvacIngestionService.writePrepared(any(), eq(true)))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED))
                .thenReturn(HvacIngestionResult.storageFailed("tdengine unavailable"));

        StandardTelemetryResult result = service.ingest(message(List.of(
                metric("CURRENT_ENERGY", "12.34", "kWh"),
                metric("LAST_PERIOD_ENERGY", "0.25", "kWh"))), LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.RETRYABLE_FAILURE);
        assertThat(result.processedMetrics()).isEqualTo(1);
        assertThat(result.shouldAcknowledge()).isFalse();
    }

    @Test
    void retainsAllLaterKnownConflictsAfterFirstStandardV1Conflict() {
        RawTelemetryEvent first = preparedEvent("POINT_1", 12.34);
        RawTelemetryEvent second = preparedEvent("POINT_2", 0.25);
        RawTelemetryEvent third = preparedEvent("POINT_3", 0.50);
        arrangePreparedBatch(first, second, third);
        when(hvacIngestionService.writePrepared(any(), eq(true)))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.CONFLICT_REJECTED));

        StandardTelemetryResult result = service.ingest(message(List.of(
                metric("CURRENT_ENERGY", "12.34", "kWh"),
                metric("LAST_PERIOD_ENERGY", "0.25", "kWh"),
                metric("TOTAL_ENERGY", "0.50", "kWh"))), LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        assertThat(result.shouldAcknowledge()).isTrue();
        verify(hvacIngestionService).writePrepared(first, true);
        ArgumentCaptor<RawTelemetryEvent> evidenceCaptor = ArgumentCaptor.forClass(RawTelemetryEvent.class);
        verify(hvacIngestionService, org.mockito.Mockito.times(2)).retainConflict(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getAllValues()).containsExactly(second, third);
    }

    @Test
    void doesNotCreateLaterUnknownRawFactAfterStandardV1Conflict() {
        RawTelemetryEvent conflicted = preparedEvent("POINT_1", 12.34);
        RawTelemetryEvent unknown = preparedEvent("POINT_2", 0.25);
        arrangePreparedBatch(conflicted, unknown);
        when(hvacIngestionService.writePrepared(any(), eq(true)))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.CONFLICT_REJECTED));

        StandardTelemetryResult result = service.ingest(message(List.of(
                metric("CURRENT_ENERGY", "12.34", "kWh"),
                metric("LAST_PERIOD_ENERGY", "0.25", "kWh"))), LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.REJECTED);
        verify(hvacIngestionService).writePrepared(conflicted, true);
        verify(hvacIngestionService, never()).writePrepared(unknown, true);
        verify(hvacIngestionService).retainConflict(unknown);
    }

    @Test
    void doesNotAcknowledgeWhenLaterConflictEvidenceCannotBeRetained() {
        RawTelemetryEvent conflicted = preparedEvent("POINT_1", 12.34);
        RawTelemetryEvent laterKnown = preparedEvent("POINT_2", 0.25);
        arrangePreparedBatch(conflicted, laterKnown);
        when(hvacIngestionService.writePrepared(any(), eq(true)))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.CONFLICT_REJECTED));
        org.mockito.Mockito.doThrow(new IllegalStateException("evidence store unavailable"))
                .when(hvacIngestionService).retainConflict(laterKnown);

        StandardTelemetryResult result = service.ingest(message(List.of(
                metric("CURRENT_ENERGY", "12.34", "kWh"),
                metric("LAST_PERIOD_ENERGY", "0.25", "kWh"))), LOCAL_RECEIVED_TIME);

        assertThat(result.outcome()).isEqualTo(StandardTelemetryOutcome.RETRYABLE_FAILURE);
        assertThat(result.shouldAcknowledge()).isFalse();
        assertThat(result.processedMetrics()).isEqualTo(1);
        verify(hvacIngestionService).retainConflict(laterKnown);
    }

    @Test
    void immutableRetryAcceptsEarlierDuplicateAndContinuesRemainingMetrics() {
        when(identityProvider.find(identityKey())).thenReturn(Optional.of(binding()));
        when(pointProvider.find(any())).thenReturn(Optional.of(
                point("POINT", "kWh", "EQUIP001", "BLD001")));
        var firstEvent=preparedEvent("POINT_1",12.34);var secondEvent=preparedEvent("POINT_2",0.25);
        prepareEvents(firstEvent,secondEvent,firstEvent,secondEvent);
        when(hvacIngestionService.writePrepared(any(),eq(true)))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED))
                .thenReturn(HvacIngestionResult.storageFailed("tdengine unavailable"))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.DUPLICATE))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED));
        StandardTelemetryMessage message = message(List.of(
                metric("CURRENT_ENERGY", "12.34", "kWh"),
                metric("LAST_PERIOD_ENERGY", "0.25", "kWh")));

        StandardTelemetryResult first = service.ingestImmutable(message, LOCAL_RECEIVED_TIME);
        StandardTelemetryResult retry = service.ingestImmutable(message, LOCAL_RECEIVED_TIME);

        assertThat(first.outcome()).isEqualTo(StandardTelemetryOutcome.RETRYABLE_FAILURE);
        assertThat(first.processedMetrics()).isEqualTo(1);
        assertThat(retry.outcome()).isEqualTo(StandardTelemetryOutcome.ACCEPTED);
        assertThat(retry.processedMetrics()).isEqualTo(2);
    }

    private StandardTelemetryMessage message(List<StandardMetric> metrics) {
        return new StandardTelemetryMessage(
                "1.0", "ENERGY_METER_V1", 1, identityKey(), EVENT_TIME,
                EVENT_TIME - 10L, "DEVICE_REPORTED", 10001L, metrics);
    }

    private StandardMetric metric(String code, String value, String unit) {
        return new StandardMetric(code, new BigDecimal(value), unit, "/" + code.toLowerCase());
    }

    private void arrangePreparedBatch(RawTelemetryEvent... events) {
        when(identityProvider.find(identityKey())).thenReturn(Optional.of(binding()));
        when(pointProvider.find(any())).thenReturn(Optional.of(
                point("POINT", "kWh", "EQUIP001", "BLD001")));
        prepareEvents(events);
    }

    private void prepareEvents(RawTelemetryEvent... events) {
        AtomicInteger index = new AtomicInteger();
        when(hvacIngestionService.prepare(anyMap(), eq(LOCAL_RECEIVED_TIME),
                eq("MQTT_STANDARD_V1")))
                .thenAnswer(invocation -> events[index.getAndIncrement()]);
    }

    private RawTelemetryEvent preparedEvent(String pointId, double value) {
        return new RawTelemetryEvent(
                pointId, pointId, "MQTT_STANDARD_V1", pointId, "METER001",
                "BLD001", "GROUP001", "EQUIP001", "METER001",
                "METER", "MAIN", pointId, value, EVENT_TIME,
                LOCAL_RECEIVED_TIME, 0, 1, false);
    }

    private DeviceIdentityKey identityKey() {
        return new DeviceIdentityKey("MAC", "123456789012345");
    }

    private DeviceIdentityBinding binding() {
        return new DeviceIdentityBinding(
                "IDENTITY001", identityKey(), "EQUIP001", "METER001",
                "BLD001", "ENERGY_METER_V1", AckMode.EVIDENCE_ONLY,
                CorrelationPolicy.NONE, null, null);
    }

    private PointRuntimeConfig point(
            String pointId, String unit, String equipmentId, String buildingId) {
        return new PointRuntimeConfig(
                pointId, pointId, pointId, buildingId, "GROUP001",
                equipmentId, "METER001", "METER", "MAIN", "VALUE",
                "ANALOG", unit, "ONLINE", 0, null, null);
    }
}
