package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ElectricityFactorSelectionTest {
    private final CarbonCalculationCore core = new CarbonCalculationCore();

    @Test
    void selectsProvinceThenActualGridThenNationalAndDoesNotHideConflicts() {
        var national = factor("N", ApplicabilityLevel.NATIONAL, null, "0.5568", LifecycleStatus.ACTIVE);
        var north = factor("R", ApplicabilityLevel.GRID_REGION, "GRID_NORTH", "0.7120", LifecycleStatus.ACTIVE);
        var northeast = factor("E", ApplicabilityLevel.GRID_REGION, "GRID_NORTHEAST", "0.6012", LifecycleStatus.ACTIVE);
        var province = factor("P", ApplicabilityLevel.PROVINCE, "150000", "0.7025", LifecycleStatus.ACTIVE);
        var activity = activity(2023, "KWH");
        assertThat(core.match(activity, "150400", ResultNature.FORMAL,
                List.of(national, north, northeast, province)).factor().factorVersionId()).isEqualTo("P");
        assertThat(core.match(activity, "150400", ResultNature.FORMAL,
                List.of(national, north, northeast)).factor().factorVersionId()).isEqualTo("E");
        assertThat(core.match(activity, "150100", ResultNature.FORMAL,
                List.of(national, north, northeast)).factor().factorVersionId()).isEqualTo("R");
        assertThat(core.match(activity, "150000", ResultNature.FORMAL,
                List.of(national, north, northeast)).factor().factorVersionId()).isEqualTo("N");
        assertThatThrownBy(() -> core.match(activity, "150400", ResultNature.FORMAL,
                List.of(national, northeast, northeast))).hasMessageContaining("多个");
    }

    @Test
    void usesDataYear2021ForAccounting2023AndConvertsMwhOnce() {
        var factor = factor("N", ApplicabilityLevel.NATIONAL, null, "0.5568", LifecycleStatus.ACTIVE);
        var activity = activity(2023, "MWH");
        var result = core.calculate(activity, core.match(activity, "110101",
                ResultNature.FORMAL, List.of(factor)), gwp("1"));
        assertThat(result.exactEmissionKgCo2e()).isEqualByComparingTo("556.8");
        assertThat(result.gwpVersionId()).isEqualTo("GWP_CO2");
        assertThat(result.evidenceJson()).contains("2021", "2023", "KG_CO2/KWH", "GAS_MASS");
        assertThatThrownBy(() -> core.match(activity(2021, "KWH"), "110101",
                ResultNature.FORMAL, List.of(factor))).hasMessageContaining("没有完整覆盖");
        assertThatThrownBy(() -> core.match(activity(2024, "KWH"), "110101",
                ResultNature.FORMAL, List.of(factor))).hasMessageContaining("没有完整覆盖");
        assertThatThrownBy(() -> core.match(activity(2023, "KG"), "110101",
                ResultNature.FORMAL, List.of(factor))).hasMessageContaining("固定比例");
    }

    @Test
    void requiresExactlyCo2GwpOneAndPreservesDisabledReportFactor() {
        var disabled = factor("OLD", ApplicabilityLevel.PROVINCE, "110000", "0.5688", LifecycleStatus.DISABLED);
        var activity = activity(2023, "KWH");
        assertThatThrownBy(() -> core.match(activity, "110101", ResultNature.FORMAL,
                List.of(disabled))).hasMessageContaining("没有完整覆盖");
        var locked = core.matchLockedElectricity(activity, List.of(disabled));
        assertThat(locked.matchReason()).contains("LOCKED_REPORT");
        assertThat(core.calculate(activity, locked, gwp("1")).exactEmissionKgCo2e())
                .isEqualByComparingTo("0.5688");
        assertThatThrownBy(() -> core.calculate(activity, locked, gwp("2")))
                .hasMessageContaining("GWP=1");
        assertThatThrownBy(() -> core.calculate(activity, locked, null)).hasMessageContaining("GWP=1");
    }

    static FactorVersion factor(String id, ApplicabilityLevel level, String region,
                                String value, LifecycleStatus status) {
        return new FactorVersion("F" + id, "EF_" + id, id, 1, ScopeType.SCOPE_2, "ELECTRICITY",
                FactorCategory.PURCHASED_ELECTRICITY_LOCATION, "GAS_MASS", "CO2", "CO2_ONLY_ELECTRICITY",
                "SOURCE2021", level, null, region, "KWH", null, UsageNature.FORMAL, status,
                LocalDateTime.of(2023, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 1, 0, 0),
                "CFV_ELECTRICITY_CO2_V1", "CRP_DECIMAL128_V1", 2, 1L,
                LocalDateTime.of(2024, 4, 12, 0, 0), 2L, LocalDateTime.now(), "test review", 2L,
                LocalDateTime.now(), List.of(new FactorComponent("C" + id,
                    ComponentType.DIRECT_EMISSION_FACTOR, new BigDecimal(value), "KG_CO2/KWH",
                    "SOURCE2021", "isolated test")), 2021, 2023);
    }

    static ActivitySegment activity(int year, String unit) {
        var zone = ZoneId.of("Asia/Shanghai");
        return new ActivitySegment("SNAPSHOT", "TEST_BUILDING", PeriodType.YEAR,
                LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
                LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant(), zone.getId(),
                "ELECTRICITY", BigDecimal.ONE, unit, "LOCKED_COMPLETE", "COMPLETE",
                ResultNature.FORMAL, "synthetic");
    }

    private static GwpVersion gwp(String value) {
        return new GwpVersion("GWP_CO2", "CO2", new BigDecimal(value), "test CO2 definition",
                UsageNature.FORMAL, LocalDateTime.of(2000, 1, 1, 0, 0), null);
    }
}
