package com.platform.iot.aggregation;

import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualRealMinuteAggregationServiceTest {

    private static final long FROM = 946_684_800_000L;
    private static final long TO = FROM + 180_000L;
    private static final long FINALIZED_AT = 1_800_000_000_000L;

    private HvacRawEventRepository rawRepository;
    private DataPointConfigProvider configProvider;
    private ManualRealMinuteAggregationService service;

    @BeforeEach
    void setUp() {
        rawRepository = mock(HvacRawEventRepository.class);
        configProvider = mock(DataPointConfigProvider.class);
        service = new ManualRealMinuteAggregationService(
                rawRepository, configProvider, new HvacPointMinuteAggregator());
    }

    @Test
    void readsExactHistoricalWindowOnceAndIncludesLateEligibleEvidence() {
        when(configProvider.findAll()).thenReturn(List.of(
                point("P2", "ONLINE", 1, "ANALOG"),
                point("P1", "ONLINE", 1, "ANALOG"),
                point("OFFLINE", "OFFLINE", 1, "ANALOG"),
                point("DIGITAL", "ONLINE", 1, "DIGITAL"),
                point("NO_CALC", "ONLINE", 0, "ANALOG"),
                point("OTHER", "ONLINE", 1, "ANALOG")));
        when(rawRepository.findWindow(FROM, TO, true)).thenReturn(List.of(
                event("P2", FROM + 61_000L, 20.0, FROM + 62_000L, false),
                event("P1", FROM + 59_000L, 12.0, FROM + 60_000L, true),
                event("P1", FROM + 1_000L, 10.0, FROM + 2_000L, false),
                event("OFFLINE", FROM + 1_000L, 30.0, FROM + 2_000L, false),
                event("DIGITAL", FROM + 1_000L, 1.0, FROM + 2_000L, false),
                event("NO_CALC", FROM + 1_000L, 40.0, FROM + 2_000L, false),
                event("OTHER", FROM + 1_000L, 50.0, FROM + 2_000L, false)));

        var result = service.aggregate(
                Set.of("P1", "P2", "OFFLINE", "DIGITAL", "NO_CALC"),
                FROM, TO, FINALIZED_AT);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(row -> row.minuteStart() + ":" + row.pointId())
                .containsExactly(FROM + ":P1", (FROM + 60_000L) + ":P2");
        assertThat(result.getFirst().averageValue()).isEqualTo(11.0);
        assertThat(result.getFirst().sampleCount()).isEqualTo(2);
        assertThat(result).allSatisfy(row -> {
            assertThat(row.dataQuality()).isZero();
            assertThat(row.finalizedAt()).isEqualTo(FINALIZED_AT);
        });
        // 平均值 11 和样本数 2 证明 late=true 的 12.0 也进入了 Q0 重聚合。
        verify(rawRepository).findWindow(FROM, TO, true);
        verify(rawRepository, times(1))
                .findWindow(anyLong(), anyLong(), eq(true));
    }

    @Test
    void emptyEvidenceProducesNoRealMinutes() {
        when(configProvider.findAll()).thenReturn(
                List.of(point("P1", "ONLINE", 1, "ANALOG")));
        when(rawRepository.findWindow(FROM, TO, true)).thenReturn(List.of());

        assertThat(service.aggregate(
                Set.of("P1"), FROM, TO, FINALIZED_AT)).isEmpty();

        verify(rawRepository).findWindow(FROM, TO, true);
    }

    @Test
    void rejectsNonHalfOpenOrNonAlignedContextBeforeReadingRawData() {
        assertThatThrownBy(() -> service.aggregate(
                Set.of("P1"), FROM, FROM, FINALIZED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("晚于");
        assertThatThrownBy(() -> service.aggregate(
                Set.of("P1"), FROM + 1L, TO, FINALIZED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分钟");
        assertThatThrownBy(() -> service.aggregate(
                Set.of("P1"), FROM, TO + 1L, FINALIZED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分钟");

        verify(rawRepository, never())
                .findWindow(anyLong(), anyLong(), eq(true));
    }

    private PointRuntimeConfig point(
            String pointId, String status, int isForCalc, String dataType) {
        return new PointRuntimeConfig(
                pointId, pointId + "_CODE", pointId, "BLD001",
                "GROUP001", "EQUIP001", "EQUIP",
                "WCR", "MAIN", "TWin", dataType, "℃",
                status, isForCalc, null, null);
    }

    private RawTelemetryEvent event(
            String pointId,
            long eventTime,
            double value,
            long receivedTime,
            boolean late) {
        return new RawTelemetryEvent(
                pointId, pointId + "_CODE", "MQTT", pointId,
                "DEVICE001", "BLD001", "GROUP001", "EQUIP001", "EQUIP",
                "WCR", "MAIN", "TWin", value, eventTime, receivedTime,
                0, 1, late);
    }
}
