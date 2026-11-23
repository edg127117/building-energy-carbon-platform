package com.platform.iot.comfort;

import com.platform.iot.comfort.ComfortQueryService.Space;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LatestRawReading;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComfortElectricityServiceTest {
    private static final long START = Instant.parse("2026-11-19T16:00:00Z").toEpochMilli();
    private static final long NOW = START + 360_001;
    private static final Space B314 = new Space("r314", "B314", "b1", "三层");
    private static final Space B313 = new Space("r313", "B313", "b1", "三层");
    private final HvacRawEventRepository raw = mock(HvacRawEventRepository.class);
    private final QualityUsagePolicyResolver quality = mock(QualityUsagePolicyResolver.class);
    private final ComfortParametersService parameters = mock(ComfortParametersService.class);
    private JdbcTemplate db;
    private ComfortElectricityService service;

    @BeforeEach
    void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:comfort-meter-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE biz_equipment(equip_id VARCHAR,building_id VARCHAR,space_id VARCHAR,del_flag INT,equip_category VARCHAR)");
        db.execute("CREATE TABLE biz_meter_coverage_revision(meter_id VARCHAR,building_id VARCHAR,revision BIGINT,effective_at BIGINT)");
        db.execute("CREATE TABLE biz_meter_coverage_target(meter_id VARCHAR,building_id VARCHAR,revision BIGINT,target_equipment_id VARCHAR,space_id VARCHAR)");
        db.execute("CREATE TABLE biz_data_point(point_id VARCHAR,building_id VARCHAR,equip_id VARCHAR,status VARCHAR,suffix_code VARCHAR,unit VARCHAR,del_flag INT DEFAULT 0)");
        db.execute("CREATE TABLE biz_comfort_emission_factor_history(building_id VARCHAR,space_id VARCHAR,effective_at BIGINT,factor DECIMAL(6,3))");
        db.update("""
                INSERT INTO biz_equipment VALUES ('meter','b1',NULL,0,'ELECTRIC_METER'),
                ('unit1','b1','r314',0,'INDOOR_UNIT'),('unit2','b1','r314',0,'INDOOR_UNIT'),
                ('unit3','b1','r314',0,'INDOOR_UNIT'),('neighbor','b1','r313',0,'INDOOR_UNIT')
                """);
        db.update("INSERT INTO biz_meter_coverage_revision VALUES ('meter','b1',1,?)", START - 360_000);
        db.update("""
                INSERT INTO biz_meter_coverage_target VALUES ('meter','b1',1,'unit1','r314'),
                ('meter','b1',1,'unit2','r314'),('meter','b1',1,'unit3','r314')
                """);
        db.update("INSERT INTO biz_data_point VALUES ('p','b1','meter','ONLINE','P','kW',0),('epp','b1','meter','ONLINE','EPP','kWh',0)");
        var allow = mock(Resolution.class);
        when(allow.decision()).thenReturn(Decision.ALLOW);
        when(quality.resolve(anyString(), anyString(), anyLong(), anyInt())).thenReturn(allow);
        when(quality.resolve(any(), anyString(), anyString(), anyLong(), anyInt())).thenReturn(allow);
        when(raw.findLatestByEquipmentPoints("b1", "meter", List.of("p")))
                .thenReturn(List.of(new LatestRawReading("p", .04, NOW - 1, NOW - 1, 0)));
        when(raw.findMeterPointHistory(eq("b1"), eq("meter"), eq("epp"), anyLong(), eq(NOW + 1), isNull(), eq(1000)))
                .thenReturn(List.of(reading(START, 72.09), reading(START + 180_000, 72.19), reading(START + 360_000, 72.29)));
        service = new ComfortElectricityService(db, raw, quality, parameters);
    }

    @Test
    void readsSharedMeterOnceAndAppliesTheFactorInEffectWithinEachInterval() {
        var key = ComfortParametersService.EMISSION_FACTOR;
        when(parameters.readManualInputs(B314)).thenReturn(Map.of(key, new BigDecimal("1.5")));
        db.update("INSERT INTO biz_comfort_emission_factor_history VALUES ('b1','r314',?,0.5),('b1','r314',?,1.5)",
                START - 1, START + 270_000);

        var result = service.read(B314, NOW);
        assertThat(result.power().watts()).isEqualByComparingTo("40");
        assertThat(result.carbonRateKgPerHour()).isEqualByComparingTo("0.06");
        assertThat(result.carbonTodayKg()).isEqualByComparingTo("0.150");
        assertThat(result.carbonHours()).singleElement().satisfies(hour -> {
            assertThat(hour.observedAt()).isEqualTo(Instant.ofEpochMilli(NOW - 1));
            assertThat(hour.cumulativeKg()).isEqualByComparingTo("0.15");
        });
        verify(raw).findLatestByEquipmentPoints("b1", "meter", List.of("p"));
        assertThat(service.read(B313, NOW).carbonStatus()).isEqualTo(ComfortElectricityService.CarbonStatus.NO_METER);
        verify(raw).findMeterPointHistory("b1", "meter", "epp", START - 360_000, NOW + 1, null, 1000);
        verifyNoMoreInteractions(raw);
    }

    @Test
    void keepsPowerWithoutCurrentFactorAndRejectsOtherRoomOnSameMeter() {
        var data = service.read(B314, NOW);
        assertThat(data.power().watts()).isEqualByComparingTo("40");
        assertThat(data.carbonStatus()).isEqualTo(ComfortElectricityService.CarbonStatus.NO_FACTOR);
        assertThat(data.carbonTodayKg()).isNull();
        assertThat(data.carbonRateKgPerHour()).isNull();
        assertThat(data.carbonHours()).isEmpty();
        verify(raw, never()).findMeterPointHistory(anyString(), anyString(), anyString(), anyLong(), anyLong(), any(), anyInt());

        db.update("INSERT INTO biz_meter_coverage_target VALUES ('meter','b1',1,'neighbor','r313')");
        assertThat(service.read(B314, NOW).power()).isNull();
        assertThat(service.read(B313, NOW).power()).isNull();
    }

    @Test
    void preservesEarlierVerifiedPointsButLeavesTotalEmptyAfterCounterGapOrMissingFactor() {
        var factors = new TreeMap<Long, BigDecimal>();
        factors.put(START, BigDecimal.ONE);
        var first = reading(START, 10);
        var second = reading(START + 180_000, 10.1);
        var later = reading(START + 541_000, 10.2);
        var gap = ComfortElectricityService.accumulate(START, List.of(first, second, later), factors, row -> true);
        assertThat(gap.total()).isNull();
        assertThat(gap.samples()).singleElement().satisfies(hour -> assertThat(hour.cumulativeKg()).isEqualByComparingTo("0.1"));
        assertThat(ComfortElectricityService.accumulate(START, List.of(first, second), new TreeMap<>(), row -> true).samples())
                .isEmpty();
    }

    @Test
    void allocatesOnlyTheAfterMidnightShareOfTheFirstMeterInterval() {
        var factors = new TreeMap<Long, BigDecimal>();
        factors.put(START, BigDecimal.ONE);
        var before = reading(START - 60_000, 72.09);
        var after = reading(START + 120_000, 72.12);
        assertThat(ComfortElectricityService.accumulate(START, List.of(before, after), factors, row -> true).total())
                .isEqualByComparingTo("0.02");

        factors.put(START + 60_000, new BigDecimal("2"));
        assertThat(ComfortElectricityService.accumulate(START, List.of(before, after), factors, row -> true).total())
                .isEqualByComparingTo("0.03");
        assertThat(ComfortElectricityService.accumulate(START, List.of(after), factors, row -> true).total()).isNull();
    }

    private static RawTelemetryEvent reading(long at, double value) {
        return new RawTelemetryEvent("epp", "IDU1_EPP", "METER", "EPP", "027", "b1", null,
                "meter", "IDU1", "1P", "MAIN", "EPP", value, at, at, 0, 0, false);
    }
}
