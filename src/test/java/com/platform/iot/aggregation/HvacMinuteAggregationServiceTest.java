package com.platform.iot.aggregation;

import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HvacMinuteAggregationServiceTest {

    private static final long MINUTE = 1_800_000_000_000L;

    @Mock private DataPointConfigProvider configProvider;
    @Mock private HvacRawEventRepository rawRepository;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private HvacMinuteAggregationService service;

    @BeforeEach
    void setUp() {
        service = new HvacMinuteAggregationService(
                configProvider, rawRepository, minuteRepository, eventPublisher,
                new HvacPointMinuteAggregator(), 30, 1);
        lenient().when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    List<RawMinuteAggregate> rows = invocation.getArgument(0);
                    return rows.stream()
                            .map(row -> new MinuteQualityWriteResult(
                                    row.pointId(), row.minuteStart(),
                                    MinuteQualityWriteResult.Outcome.INSERTED, null, null))
                            .toList();
                });
        lenient().when(configProvider.findAll()).thenReturn(List.of(
                point("WCR1_TWin", "WCR1", "TWin"),
                point("DBO_RH", null, "RH")));
    }

    @Test
    void queriesOneWholeWindowAndGroupsMultiplePointsIntoOneSave() {
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of(
                        event("WCR1_TWin", "WCR1", "TWin", 12.0, 5_000L, 6_000L),
                        event("DBO_RH", null, "RH", 60.0, 10_000L, 11_000L),
                        event("WCR1_TWin", "WCR1", "TWin", 14.0, 50_000L, 52_000L)));

        service.finalizeDueMinutes(MINUTE + 90_000L);

        verify(rawRepository, times(1))
                .findWindow(MINUTE, MINUTE + 60_000L, false);
        List<RawMinuteAggregate> batch = capturedBatch();
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).pointCode()).isEqualTo("WCR1_TWin");
        assertThat(batch.get(0).averageValue()).isEqualTo(13.0);
        assertThat(batch.get(0).minimumValue()).isEqualTo(12.0);
        assertThat(batch.get(0).maximumValue()).isEqualTo(14.0);
        assertThat(batch.get(0).sampleCount()).isEqualTo(2);
        assertThat(batch.get(1).pointCode()).isEqualTo("DBO_RH");
        assertThat(batch.get(1).averageValue()).isEqualTo(60.0);
    }

    @Test
    void savesBeforePublishingFormulaHandoffEvent() {
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of(
                        event("WCR1_TWin", "WCR1", "TWin", 12.3, 5_000L, 6_000L)));

        service.finalizeDueMinutes(MINUTE + 90_000L);

        InOrder order = inOrder(minuteRepository, eventPublisher);
        order.verify(minuteRepository).saveAllWithQualityPriority(anyList(), isNull());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        order.verify(eventPublisher).publishEvent(eventCaptor.capture());
        HvacMinuteBatchFrozenEvent frozen =
                (HvacMinuteBatchFrozenEvent) eventCaptor.getValue();
        assertThat(frozen.minuteStart()).isEqualTo(MINUTE);
        assertThat(frozen.recovery()).isFalse();
        assertThat(frozen.buildingIds()).containsExactly("BLD001");
        assertThat(frozen.aggregates()).hasSize(1);
    }

    @Test
    void doesNotPublishRejectedLowerQualityMinute() {
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of(
                        event("WCR1_TWin", "WCR1", "TWin", 12.3, 5_000L, 6_000L)));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "POINT_WCR", MINUTE,
                        MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY,
                        0, null)));

        service.finalizeDueMinutes(MINUTE + 90_000L);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void doesNotProcessTargetMinuteBeforeThirtySecondBoundary() {
        service.finalizeDueMinutes(MINUTE + 89_999L);

        verify(rawRepository, never())
                .findWindow(MINUTE, MINUTE + 60_000L, false);
    }

    @Test
    void doesNotRepeatSameMinuteOnEveryTenSecondTick() {
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of());

        service.finalizeDueMinutes(MINUTE + 90_000L);
        service.finalizeDueMinutes(MINUTE + 99_999L);

        verify(rawRepository, times(1))
                .findWindow(MINUTE, MINUTE + 60_000L, false);
        verifyNoInteractions(minuteRepository);
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void emptyRealMinuteStillPublishesBuildingsForQualityCompletion() {
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of());

        service.finalizeDueMinutes(MINUTE + 90_000L);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        HvacMinuteBatchFrozenEvent frozen =
                (HvacMinuteBatchFrozenEvent) eventCaptor.getValue();
        assertThat(frozen.aggregates()).isEmpty();
        assertThat(frozen.buildingIds()).containsExactly("BLD001");
        verifyNoInteractions(minuteRepository);
    }

    @Test
    void retriesSameMinuteWhenBatchSaveFails() {
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of(
                        event("WCR1_TWin", "WCR1", "TWin", 12.3, 5_000L, 6_000L)));
        doThrow(new IllegalStateException("TDengine unavailable"))
                .doReturn(List.of(new MinuteQualityWriteResult(
                        "POINT_WCR", MINUTE,
                        MinuteQualityWriteResult.Outcome.INSERTED, null, null)))
                .when(minuteRepository).saveAllWithQualityPriority(anyList(), isNull());

        service.finalizeDueMinutes(MINUTE + 90_000L);
        service.finalizeDueMinutes(MINUTE + 95_000L);

        verify(rawRepository, times(2))
                .findWindow(MINUTE, MINUTE + 60_000L, false);
        verify(minuteRepository, times(2))
                .saveAllWithQualityPriority(anyList(), isNull());
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void recoveryQueriesOnceAndWritesOnlyMissingPointCodes() {
        PointRuntimeConfig buildingTwo = new PointRuntimeConfig(
                "POINT_B2", "WCR2_TWin", "二号楼进水温度",
                "BLD002", "GROUP_B2", "EQUIP_B2", "WCR2",
                "WCR", "MAIN", "TWin", "ANALOG", "℃",
                "ONLINE", 1, null, null);
        when(configProvider.findAll()).thenReturn(List.of(
                point("WCR1_TWin", "WCR1", "TWin"),
                point("DBO_RH", null, "RH"),
                buildingTwo));
        when(minuteRepository.findExistingPointIds(MINUTE))
                .thenReturn(Set.of("POINT_WCR", "POINT_B2"));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of(
                        event("WCR1_TWin", "WCR1", "TWin", 12.3, 5_000L, 6_000L),
                        event("DBO_RH", null, "RH", 60.0, 10_000L, 11_000L)));

        service.recoverRecentMinutes(MINUTE + 90_000L);

        List<RawMinuteAggregate> batch = capturedBatch();
        assertThat(batch).extracting(RawMinuteAggregate::pointCode)
                .containsExactly("DBO_RH");
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        HvacMinuteBatchFrozenEvent frozen =
                (HvacMinuteBatchFrozenEvent) eventCaptor.getValue();
        assertThat(frozen.recovery()).isTrue();
        assertThat(frozen.buildingIds()).containsExactly("BLD001");
    }

    @Test
    void recoverySkipsRawQueryWhenAllPointsAlreadyExist() {
        when(minuteRepository.findExistingPointIds(MINUTE))
                .thenReturn(Set.of("POINT_WCR", "POINT_DBO"));

        service.recoverRecentMinutes(MINUTE + 90_000L);

        verify(rawRepository, never()).findWindow(anyLong(), anyLong(), anyBoolean());
        verify(minuteRepository, never())
                .saveAllWithQualityPriority(anyList(), isNull());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void sameCanonicalCodeInTwoBuildingsAggregatesByDifferentPointIds() {
        PointRuntimeConfig buildingOne = new PointRuntimeConfig(
                "POINT_B1", "WCR1_TWin", "一号楼进水温度",
                "BLD001", "GROUP_B1", "EQUIP_B1", "WCR1",
                "WCR", "MAIN", "TWin", "ANALOG", "℃",
                "ONLINE", 1, null, null);
        PointRuntimeConfig buildingTwo = new PointRuntimeConfig(
                "POINT_B2", "WCR1_TWin", "二号楼进水温度",
                "BLD002", "GROUP_B2", "EQUIP_B2", "WCR1",
                "WCR", "MAIN", "TWin", "ANALOG", "℃",
                "ONLINE", 1, null, null);
        when(configProvider.findAll()).thenReturn(List.of(buildingOne, buildingTwo));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, false))
                .thenReturn(List.of(
                        eventFor(buildingOne, 12.0),
                        eventFor(buildingTwo, 18.0)));

        service.finalizeDueMinutes(MINUTE + 90_000L);

        List<RawMinuteAggregate> batch = capturedBatch();
        assertThat(batch).extracting(RawMinuteAggregate::pointId)
                .containsExactly("POINT_B1", "POINT_B2");
        assertThat(batch).extracting(RawMinuteAggregate::buildingId)
                .containsExactly("BLD001", "BLD002");
        assertThat(batch).extracting(RawMinuteAggregate::averageValue)
                .containsExactly(12.0, 18.0);
    }

    @SuppressWarnings("unchecked")
    private List<RawMinuteAggregate> capturedBatch() {
        ArgumentCaptor<List<RawMinuteAggregate>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(minuteRepository).saveAllWithQualityPriority(captor.capture(), isNull());
        return captor.getValue();
    }

    private RawTelemetryEvent event(
            String pointCode,
            String equipId,
            String suffixCode,
            double value,
            long eventOffset,
            long receiveOffset) {
        return new RawTelemetryEvent(
                pointId(pointCode), pointCode,
                "MQTT_FREEZE_V1", pointCode, equipId == null ? "WEATHER_GATEWAY" : equipId,
                "BLD001", "GROUP001", equipId, equipId,
                pointCode.startsWith("DBO") ? "RHO" : "WCR", "MAIN", suffixCode, value,
                MINUTE + eventOffset, MINUTE + receiveOffset,
                0, 1, false);
    }

    private PointRuntimeConfig point(String pointCode, String equipId, String suffixCode) {
        return new PointRuntimeConfig(
                pointId(pointCode), pointCode, pointCode, "BLD001", "GROUP001",
                equipId, equipId, pointCode.startsWith("DBO") ? "RHO" : "WCR",
                "MAIN", suffixCode, "ANALOG",
                pointCode.startsWith("DBO") ? "%" : "℃",
                "ONLINE", 1, null, null);
    }

    private String pointId(String pointCode) {
        return pointCode.startsWith("DBO") ? "POINT_DBO" : "POINT_WCR";
    }

    private RawTelemetryEvent eventFor(PointRuntimeConfig point, double value) {
        return new RawTelemetryEvent(
                point.pointId(), point.pointCode(),
                "MQTT_FREEZE_V1", "WCR1_TWin", "WCR1",
                point.buildingId(), point.systemGroupId(), point.equipId(), point.equipCode(),
                point.familyCode(), point.componentCode(), point.suffixCode(), value,
                MINUTE + 1_000L, MINUTE + 2_000L, 0, 1, false);
    }
}
