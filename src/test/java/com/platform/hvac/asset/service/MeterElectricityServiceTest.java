package com.platform.hvac.asset.service;

import com.platform.framework.common.Result;
import com.platform.hvac.asset.api.MeterCoverageContracts;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MeterElectricityServiceTest {
    private final MeterCoverageRepository meters = mock(MeterCoverageRepository.class);
    private final MeterCoverageService coverage = mock(MeterCoverageService.class);
    private final BizDataPointService points = mock(BizDataPointService.class);
    private final HvacRawEventRepository rawEvents = mock(HvacRawEventRepository.class);
    private final QualityUsagePolicyResolver quality = mock(QualityUsagePolicyResolver.class);
    private final MeterElectricityService service = new MeterElectricityService(
            meters, coverage, points, rawEvents, quality);

    @Test
    void rejectsUnauthorizedOrWrongBuildingBeforeReadingHistory() {
        assertThatThrownBy(() -> service.analyze("M-027", "BLD001", 7, Set.of("ENERGY_MANAGER")))
                .hasMessageContaining("平台管理员");
        verifyNoInteractions(meters, points, rawEvents);

        when(meters.equipment("M-027", false)).thenReturn(Optional.of(meter()));
        assertThatThrownBy(() -> service.analyze("M-027", "BLD002", 7, Set.of("PLATFORM_ADMIN")))
                .hasMessageContaining("不属于指定建筑");
        verifyNoInteractions(points, rawEvents);
    }

    @Test
    void usesConfiguredEppAndReturnsMissingDaysWithoutFabricatingZero() {
        when(meters.equipment("M-027", false)).thenReturn(Optional.of(meter()));
        BizDataPoint point = new BizDataPoint();
        point.setPointId("EPP-027");
        point.setPointCode("IDU1_EPP");
        point.setBuildingId("BLD001");
        point.setStatus("ONLINE");
        point.setUnit("kWh");
        when(points.listByEquip("M-027")).thenReturn(Result.success(List.of(point)));
        when(coverage.current(eq("M-027"), anyCollection())).thenReturn(new MeterCoverageContracts.View(
                "M-027", 2, null, "S3", "三楼", "B314 三台内机", null,
                List.of(), "GROUP_ONLY", "SEPARATE_ONLY"));

        var result = service.analyze("M-027", "BLD001", 7, Set.of("PLATFORM_ADMIN"));

        assertThat(result.days()).hasSize(7).allSatisfy(day -> {
            assertThat(day.kwh()).isNull();
            assertThat(day.status()).isEqualTo("MISSING");
        });
        assertThat(result.currentCoverage().scopeLabel()).isEqualTo("B314 三台内机");
        verify(rawEvents, atLeastOnce()).findMeterPointHistory(eq("BLD001"), eq("M-027"),
                eq("EPP-027"), anyLong(), anyLong(), isNull(), eq(1000));
    }

    private static MeterCoverageRepository.Equipment meter() {
        return new MeterCoverageRepository.Equipment("M-027", "BLD001", "IDU1", "单相电表027",
                null, null, true, false);
    }
}
