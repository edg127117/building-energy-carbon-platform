package com.platform.iot.daikin.monitoring;

import com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryService;
import com.platform.iot.quality.*;
import com.platform.iot.qualityusage.QualityUsageModels.*;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LatestRawReading;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaikinTemperatureQueryServiceTest {
    private static final long NOW = Instant.parse("2026-09-16T00:00:00Z").toEpochMilli();
    private final DaikinMonitoringQueryService access = mock(DaikinMonitoringQueryService.class);
    private final DataPointConfigProvider points = mock(DataPointConfigProvider.class);
    private final HvacRawEventRepository raw = mock(HvacRawEventRepository.class);
    private final QualityUsagePolicyResolver quality = mock(QualityUsagePolicyResolver.class);
    private DaikinTemperatureQueryService service;

    @BeforeEach
    void setup() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:tempquery" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE biz_device_identity(identity_value VARCHAR(50),equip_id VARCHAR(32),building_id VARCHAR(32),identity_type VARCHAR(30))");
        jdbc.update("INSERT INTO biz_device_identity VALUES ('identity','e','b','DAIKIN_UNIT')");
        jdbc.execute("CREATE TABLE biz_daikin_current_state(field_status VARCHAR(20),equipment_id VARCHAR(32),building_id VARCHAR(32),field_name VARCHAR(30),last_attempt_at_ms BIGINT,last_valid_at_ms BIGINT)");
        jdbc.update("INSERT INTO biz_daikin_current_state VALUES ('MISSING','e','b','roomTemp',?,?)", NOW, NOW - 600000);
        when(access.requireEquipment(1L, Set.of("OPS"), "e")).thenReturn("b");
        when(points.find(any())).thenReturn(Optional.of(new PointRuntimeConfig("p", "PC", "温度", "b", null, "e", "EC", "AHU", "MAIN", "T", "AI", "°C", "ONLINE", 0, null, null)));
        service = new DaikinTemperatureQueryService(jdbc, access, points, raw, quality, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void ninetyDayBoundaryAndPageLimitAreEnforcedBeforeStorageQuery() {
        long from = NOW - 90L * 86400000;
        when(raw.findPointHistory("b", "e", "p", from, NOW, null, 3)).thenReturn(List.of(event(from), event(from + 60000), event(from + 120000)));
        when(quality.resolve(any(), eq("p"), eq("POINT_HISTORY_VIEW"), anyLong(), eq(0))).thenReturn(resolution("POINT_HISTORY_VIEW", Decision.ALLOW));
        var result = service.history(1L, Set.of("OPS"), "e", "roomTemp", from, NOW, null, 2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.nextCursor()).isEqualTo(from + 60000);
        assertThatThrownBy(() -> service.history(1L, Set.of("OPS"), "e", "roomTemp", from - 1, NOW, null, 2)).hasMessageContaining("90天");
        assertThatThrownBy(() -> service.history(1L, Set.of("OPS"), "e", "roomTemp", from, NOW, null, 1001)).hasMessageContaining("90天");
        verify(raw, times(1)).findPointHistory(anyString(), anyString(), anyString(), anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void blockedCurrentReadingHasNoValueAndKeepsOriginalTimeAndMissingStatus() {
        when(raw.findPointHistory("b", "e", "p", NOW - 600000, NOW - 600000 + 1, null, 1)).thenReturn(List.of(event(NOW - 600000)));
        when(quality.resolve(eq("p"), eq("POINT_REALTIME_VIEW"), anyLong(), eq(0))).thenReturn(resolution("POINT_REALTIME_VIEW", Decision.BLOCK));
        var current = service.current(1L, Set.of("OPS"), "e", "roomTemp");
        assertThat(current.reading().value()).isNull();
        assertThat(current.reading().stale()).isTrue();
        assertThat(current.reading().observedAt()).isEqualTo(NOW - 600000);
        assertThat(current.fieldStatus()).isEqualTo("MISSING");
    }

    @Test
    void blockedHistoryKeepsGapAndQualityWithoutRevealingValue() {
        when(raw.findPointHistory("b", "e", "p", NOW - 900000, NOW, null, 6)).thenReturn(List.of(event(NOW - 60000)));
        when(quality.resolve(any(), eq("p"), eq("POINT_HISTORY_VIEW"), anyLong(), eq(0))).thenReturn(resolution("POINT_HISTORY_VIEW", Decision.BLOCK));
        var row = service.history(1L, Set.of("OPS"), "e", "roomTemp", NOW - 900000, NOW, null, 5).items().getFirst();
        assertThat(row.value()).isNull();
        assertThat(row.gapBefore()).isTrue();
        assertThat(row.quality().decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void forbiddenEquipmentCannotReachPointOrHistoryStorage() {
        when(access.requireEquipment(any(), any(), any())).thenThrow(new IllegalStateException("forbidden"));
        assertThatThrownBy(() -> service.history(1L, Set.of("OPS"), "e", "roomTemp", NOW - 60000, NOW, null, 5)).hasMessage("forbidden");
        verifyNoInteractions(raw);
    }

    private Resolution resolution(String scenario, Decision decision) { return new Resolution(decision, 0, scenario, PolicySource.PUBLISHED_POLICY, 1, 1, "TEST"); }
    private RawTelemetryEvent event(long time) {
        return new RawTelemetryEvent("p", "PC", "DAIKIN_V2", "alias", "EC", "b", null, "e", "EC", "AHU", "MAIN", "T", 23.5, time, time, 0, 0, false);
    }
}
