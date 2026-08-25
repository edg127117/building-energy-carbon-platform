package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.Building;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyInterval;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.Scenario;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageTestFixtures;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.iot.qualityusage.QualityUsageModels.POINT_HISTORY_VIEW;
import static com.platform.iot.qualityusage.QualityUsageModels.POINT_REALTIME_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class HvacQueryQualityUsageGateTest {
    private final BuildingService buildingService = mock(BuildingService.class);
    private final BuildingScopeService scopeService = mock(BuildingScopeService.class);
    private final BizDataPointService pointService = mock(BizDataPointService.class);
    private final BizEquipmentService equipmentService = mock(BizEquipmentService.class);
    private final HvacMinuteRepository minuteRepository = mock(HvacMinuteRepository.class);
    private final HvacSnapshotFreshnessPolicy freshnessPolicy =
            mock(HvacSnapshotFreshnessPolicy.class);
    private HvacQueryService service;

    @BeforeEach
    void setUp() {
        Building building = new Building();
        building.setBuildingId("BLD001");
        when(buildingService.getById("BLD001")).thenReturn(building);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void realtimeNeverLeaksBlockedValue() {
        BizDataPoint point = point();
        when(pointService.list(any(Wrapper.class)))
                .thenReturn(List.of(point));
        when(minuteRepository.findLatestByPointIds(List.of("POINT001")))
                .thenReturn(List.of(row(0, 12.0, 1)));
        service = service(POINT_REALTIME_VIEW, Set.of(QualityLevel.Q0));

        var result = service.snapshot("BLD001", 1L, Set.of("PLATFORM_ADMIN"));

        assertThat(result.points().get(0).usageStatus()).isEqualTo("QUALITY_BLOCKED");
        assertThat(result.points().get(0).average()).isNull();
        assertThat(result.points().get(0).actualQuality()).isEqualTo(1);
        assertThat(result.points().get(0).configRevision()).isEqualTo(3);
    }

    @Test
    void filtersMinutesBeforeFiveMinuteDownsampling() {
        long to = 86_400_001L;
        BizDataPoint point = point();
        when(pointService.listByIds(List.of("POINT001"))).thenReturn(List.of(point));
        when(minuteRepository.findHistory(List.of("POINT001"), 0, to, 1))
                .thenReturn(List.of(
                        row(0, 10.0, 0),
                        row(60_000, 20.0, 1),
                        row(120_000, 30.0, 2)));
        service = service(POINT_HISTORY_VIEW,
                Set.of(QualityLevel.Q0, QualityLevel.Q2));

        var result = service.history(
                "BLD001", "POINT001", 0L, to, 1L, Set.of("PLATFORM_ADMIN"));
        var first = result.series().get(0).records().get(0);

        assertThat(result.resolutionMinutes()).isEqualTo(5);
        assertThat(first.average()).isEqualTo(20.0);
        assertThat(first.usableCount()).isEqualTo(2);
        assertThat(first.blockedCount()).isEqualTo(1);
        assertThat(first.missingCount()).isEqualTo(2);
        assertThat(first.status()).isEqualTo("PARTIAL_POLICY_FILTERED");
        assertThat(first.dataQuality()).isEqualTo(2);
    }

    private HvacQueryService service(String scenarioCode, Set<QualityLevel> levels) {
        QualityUsagePolicyResolver resolver = QualityUsageTestFixtures.resolver(3,
                Map.of(scenarioCode, new Scenario(
                        scenarioCode,
                        scenarioCode.equals(POINT_REALTIME_VIEW)
                                ? "POINT_REALTIME_GATE" : "POINT_HISTORY_GATE",
                        "ENABLED")),
                Map.of(new PolicyKey("POINT001", scenarioCode),
                        List.of(new PolicyInterval("POL001", 2, null, null, levels))));
        return new HvacQueryService(
                buildingService, scopeService, pointService, equipmentService,
                minuteRepository, freshnessPolicy, resolver);
    }

    private BizDataPoint point() {
        BizDataPoint point = new BizDataPoint();
        point.setPointId("POINT001");
        point.setPointCode("P1");
        point.setPointName("Point");
        point.setBuildingId("BLD001");
        point.setStatus("ONLINE");
        return point;
    }

    private HvacMinuteQueryRow row(long minute, double value, int quality) {
        return new HvacMinuteQueryRow(
                "POINT001", minute, value, value, value, 1, quality);
    }
}
