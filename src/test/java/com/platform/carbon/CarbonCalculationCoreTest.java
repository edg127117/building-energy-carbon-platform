package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarbonCalculationCoreTest {
    private final CarbonCalculationCore core = new CarbonCalculationCore();

    @Test
    void calculatesStationaryCombustionWithoutIntermediateRounding() {
        ActivitySegment activity = activity("DIESEL", new BigDecimal("10"), "KG");
        FactorVersion factor = factor("DIESEL", FactorCategory.STATIONARY_COMBUSTION,
                ScopeType.SCOPE_1, ApplicabilityLevel.NATIONAL, null,
                List.of(component(ComponentType.LOWER_HEATING_VALUE, "2", "MJ/KG"),
                        component(ComponentType.CARBON_CONTENT_PER_HEAT, "0.5", "KG_C_PER_MJ"),
                        component(ComponentType.OXIDATION_RATE, "0.9", "RATIO")));

        CalculatedItem result = core.calculate(activity,
                core.match(activity, "310000", ResultNature.DEVELOPMENT_SIMULATION,
                        List.of(factor)), gwp());

        assertThat(result.exactEmissionKgCo2e()).isEqualByComparingTo("33.0");
        assertThat(result.gwpVersionId()).isEqualTo("CGWP_CO2_V1");
        assertThat(result.evidenceJson()).contains("\"rawKgCO2e\":\"33.000000");
    }

    @Test
    void usesProvincialElectricityBeforeNationalAndNeverFallsBackToGlobal() {
        ActivitySegment activity = activity("ELECTRICITY", new BigDecimal("1000"), "KWH");
        FactorVersion national = direct("EF-NATIONAL", "ELECTRICITY",
                FactorCategory.PURCHASED_ELECTRICITY_LOCATION,
                ApplicabilityLevel.NATIONAL, null, "0.5");
        FactorVersion provincial = direct("EF-PROVINCE", "ELECTRICITY",
                FactorCategory.PURCHASED_ELECTRICITY_LOCATION,
                ApplicabilityLevel.PROVINCE, "310000", "0.4");
        FactorVersion invalidGlobal = direct("EF-NON-REGION", "ELECTRICITY",
                FactorCategory.PURCHASED_ELECTRICITY_LOCATION,
                ApplicabilityLevel.NOT_REGION_SPECIFIC, null, "0.1");

        FactorMatch match = core.match(activity, "310000",
                ResultNature.DEVELOPMENT_SIMULATION,
                List.of(national, provincial, invalidGlobal));

        assertThat(match.factor().factorVersionId()).isEqualTo("EF-PROVINCE");
        assertThat(core.calculate(activity, match, null).exactEmissionKgCo2e())
                .isEqualByComparingTo("400.0");
    }

    @Test
    void generatesOnlyAnnualTotalAreaAndPopulationIntensities() {
        CalculatedItem scope1 = calculated("DIESEL", ScopeType.SCOPE_1, "300");
        CalculatedItem scope2 = calculated("ELECTRICITY", ScopeType.SCOPE_2, "700");
        DenominatorVersion area = denominator(DenominatorType.BUILDING_AREA, "100", "M2");

        List<SummaryMetric> annual = core.summarize(List.of(scope1, scope2),
                PeriodType.YEAR, area, null);
        List<SummaryMetric> monthly = core.summarize(List.of(scope1, scope2),
                PeriodType.MONTH, area, denominator(DenominatorType.RESIDENT_POPULATION,
                        "10", "PERSON"));

        assertThat(annual).filteredOn(value -> value.metricCode().endsWith("INTENSITY"))
                .extracting(SummaryMetric::metricCode)
                .containsExactlyInAnyOrder("AREA_INTENSITY", "POPULATION_INTENSITY");
        assertThat(metric(annual, "AREA_INTENSITY").finalValue()).isEqualByComparingTo("10.000000");
        assertThat(metric(annual, "POPULATION_INTENSITY").finalValue()).isNull();
        assertThat(metric(annual, "POPULATION_INTENSITY").unavailableReason())
                .contains("常驻人数");
        assertThat(monthly).noneMatch(value -> value.metricCode().endsWith("INTENSITY"));
    }

    @Test
    void keepsSharesEmptyWhenTotalEmissionIsZero() {
        List<SummaryMetric> summaries = core.summarize(List.of(
                calculated("ELECTRICITY", ScopeType.SCOPE_2, "0")),
                PeriodType.QUARTER, null, null);

        assertThat(summaries).filteredOn(value -> value.metricCode().endsWith("SHARE"))
                .allSatisfy(value -> {
                    assertThat(value.finalValue()).isNull();
                    assertThat(value.unavailableReason()).contains("总排放量为零");
                });
    }

    @Test
    void denominatorConflictLeavesOnlyThatIntensityUnavailable() {
        List<SummaryMetric> summaries = core.summarizeWithDenominatorSelections(List.of(
                        calculated("DIESEL", ScopeType.SCOPE_1, "300"),
                        calculated("ELECTRICITY", ScopeType.SCOPE_2, "700")),
                PeriodType.YEAR,
                CarbonCalculationCore.DenominatorSelection.unavailable("建筑面积版本冲突"),
                CarbonCalculationCore.DenominatorSelection.available(
                        denominator(DenominatorType.RESIDENT_POPULATION, "10", "PERSON")));

        assertThat(metric(summaries, "TOTAL_EMISSION").finalValue())
                .isEqualByComparingTo("1.000000");
        assertThat(metric(summaries, "AREA_INTENSITY").finalValue()).isNull();
        assertThat(metric(summaries, "AREA_INTENSITY").unavailableReason())
                .isEqualTo("建筑面积版本冲突");
        assertThat(metric(summaries, "POPULATION_INTENSITY").finalValue())
                .isEqualByComparingTo("100.000000");
    }

    @Test
    void rejectsDensityOrStandardConditionConversions() {
        assertThatThrownBy(() -> CarbonCalculationCore.convert(
                BigDecimal.ONE, "M3", "KG"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.UNIT_INCOMPATIBLE);
    }

    private static SummaryMetric metric(List<SummaryMetric> values, String code) {
        return values.stream().filter(value -> value.metricCode().equals(code)).findFirst().orElseThrow();
    }

    private static ActivitySegment activity(String item, BigDecimal quantity, String unit) {
        return new ActivitySegment("SNAPSHOT-1", "BLD001", PeriodType.YEAR,
                Instant.parse("2025-12-31T16:00:00Z"),
                Instant.parse("2026-12-31T16:00:00Z"), "Asia/Shanghai", item,
                quantity, unit, "LOCKED_COMPLETE", "COMPLETE",
                ResultNature.DEVELOPMENT_SIMULATION, "ACTIVITY-HASH");
    }

    private static FactorVersion direct(String id, String item, FactorCategory category,
                                        ApplicabilityLevel level, String region, String value) {
        return factor(item, category, ScopeType.SCOPE_2, level, region,
                List.of(component(ComponentType.DIRECT_EMISSION_FACTOR, value,
                        "KG_CO2E/KWH")), id);
    }

    private static FactorVersion factor(String item, FactorCategory category, ScopeType scope,
                                        ApplicabilityLevel level, String region,
                                        List<FactorComponent> components) {
        return factor(item, category, scope, level, region, components, "FV-1");
    }

    private static FactorVersion factor(String item, FactorCategory category, ScopeType scope,
                                        ApplicabilityLevel level, String region,
                                        List<FactorComponent> components, String id) {
        String input = category == FactorCategory.STATIONARY_COMBUSTION ? "KG" : "KWH";
        return new FactorVersion("F-1", "FACTOR-1", id, 1, scope, item, category,
                category == FactorCategory.STATIONARY_COMBUSTION ? "GAS_MASS" : "CO2E_DIRECT",
                "CO2", category == FactorCategory.STATIONARY_COMBUSTION
                ? "CO2_ONLY_FOR_STATIONARY_COMBUSTION" : "DIRECT_FACTOR_GAS_COVERAGE",
                "SOURCE-1", level, null, region, input, null,
                UsageNature.DEVELOPMENT_REFERENCE, LifecycleStatus.ACTIVE,
                LocalDateTime.of(2020, 1, 1, 0, 0), null,
                category == FactorCategory.STATIONARY_COMBUSTION
                        ? "CFV_STATIONARY_CO2_V1" : "CFV_ELECTRICITY_CO2E_V1",
                "CRP_DECIMAL128_V1", 1, 1L, LocalDateTime.now(), 2L,
                LocalDateTime.now(), "审核通过", 3L, LocalDateTime.now(), components);
    }

    private static FactorComponent component(ComponentType type, String value, String unit) {
        return new FactorComponent(type.name(), type, new BigDecimal(value), unit,
                "SOURCE-1", "测试证据");
    }

    private static CalculatedItem calculated(String item, ScopeType scope, String emission) {
        FactorCategory category = scope == ScopeType.SCOPE_1
                ? FactorCategory.STATIONARY_COMBUSTION
                : FactorCategory.PURCHASED_ELECTRICITY_LOCATION;
        FactorVersion factor = factor(item, category, scope, ApplicabilityLevel.NATIONAL,
                null, category == FactorCategory.STATIONARY_COMBUSTION
                        ? List.of(component(ComponentType.LOWER_HEATING_VALUE, "1", "MJ/KG"),
                        component(ComponentType.CARBON_CONTENT_PER_HEAT, "1", "KG_C_PER_MJ"),
                        component(ComponentType.OXIDATION_RATE, "1", "RATIO"))
                        : List.of(component(ComponentType.DIRECT_EMISSION_FACTOR, "1",
                        "KG_CO2E/KWH")));
        BigDecimal value = new BigDecimal(emission);
        return new CalculatedItem(activity(item, BigDecimal.ONE,
                category == FactorCategory.STATIONARY_COMBUSTION ? "KG" : "KWH"),
                factor, BigDecimal.ONE, factor.formulaVersionId(), null, value,
                value.setScale(18), "测试匹配", "{}", "HASH");
    }

    private static DenominatorVersion denominator(DenominatorType type, String value, String unit) {
        return new DenominatorVersion("D-1", "DV-1", 1, "BLD001", type,
                new BigDecimal(value), unit, "测试来源", "测试证据",
                UsageNature.DEVELOPMENT_REFERENCE, LifecycleStatus.ACTIVE,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2027, 1, 1),
                1, 1L, LocalDateTime.now(), 2L, LocalDateTime.now(), "审核通过",
                3L, LocalDateTime.now());
    }

    private static GwpVersion gwp() {
        return new GwpVersion("CGWP_CO2_V1", "CO2", BigDecimal.ONE,
                "测试GWP来源", UsageNature.DEVELOPMENT_REFERENCE,
                LocalDateTime.of(2020, 1, 1, 0, 0), null);
    }
}
