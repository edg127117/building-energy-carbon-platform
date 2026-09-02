package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 验证首版单次 2000 条硬上限在同步计算超时预算内完成纯计算。 */
class CarbonCalculationCapacityTest {

    @Test
    void calculatesConfiguredMaximumDetailsWithinSynchronousCpuBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CarbonCalculationCore core = new CarbonCalculationCore();
            FactorVersion factor = factor();
            List<CalculatedItem> items = new ArrayList<>(2_000);
            for (int index = 0; index < 2_000; index++) {
                ActivitySegment activity = new ActivitySegment("S" + index, "BLD001",
                        PeriodType.MONTH, Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-02-01T00:00:00Z"), "UTC", "ELECTRICITY",
                        new BigDecimal("1000"), "KWH", "LOCKED_COMPLETE", "COMPLETE",
                        ResultNature.DEVELOPMENT_SIMULATION, "e".repeat(64));
                items.add(core.calculate(activity,
                        core.match(activity, "310000", ResultNature.DEVELOPMENT_SIMULATION,
                                List.of(factor)), null));
            }

            assertThat(items).hasSize(2_000);
            assertThat(core.summarize(items, PeriodType.MONTH, null, null))
                    .filteredOn(value -> "TOTAL_EMISSION".equals(value.metricCode()))
                    .singleElement().satisfies(value -> assertThat(value.finalValue())
                            .isEqualByComparingTo("1000.000000"));
        });
    }

    private static FactorVersion factor() {
        FactorComponent component = new FactorComponent("C1",
                ComponentType.DIRECT_EMISSION_FACTOR, new BigDecimal("0.5"),
                "KG_CO2E/KWH", "SOURCE1", "测试证据");
        return new FactorVersion("F1", "ELECTRICITY_FACTOR", "FV1", 1,
                ScopeType.SCOPE_2, "ELECTRICITY",
                FactorCategory.PURCHASED_ELECTRICITY_LOCATION, "CO2E_DIRECT", "CO2E",
                "DIRECT_FACTOR_GAS_COVERAGE", "SOURCE1", ApplicabilityLevel.NATIONAL,
                null, null, "KWH", null, UsageNature.DEVELOPMENT_REFERENCE,
                LifecycleStatus.ACTIVE, LocalDateTime.of(2020, 1, 1, 0, 0), null,
                "CFV_ELECTRICITY_CO2E_V1", "CRP_DECIMAL128_V1", 1, 1L,
                LocalDateTime.now(), 2L, LocalDateTime.now(), "审核通过", 3L,
                LocalDateTime.now(), List.of(component));
    }
}
