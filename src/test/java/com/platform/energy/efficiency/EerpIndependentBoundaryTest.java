package com.platform.energy.efficiency;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.platform.energy.activity.EnergyActivityDataReader.RawEvent;
import com.platform.iot.calculation.CalculationPointReadService;
import com.platform.iot.qualityusage.QualityUsageTestFixtures;
import com.platform.iot.qualityusage.QualityUsageModels.*;
import static com.platform.energy.efficiency.CoolingComputationCore.*;
import static org.assertj.core.api.Assertions.*;

class EerpIndependentBoundaryTest {
    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void indicatorPolicyControlsPublicCumulativeChainIncludingEmptyAndUnavailablePolicy() {
        for (int scenario = 0; scenario < 7; scenario++) {
            var f = new EerpServiceTest(); f.setup();
            var config = f.activate(f.configuration(false)); f.baselineHour();
            int q = scenario == 1 || scenario == 3 ? 1 : scenario == 2 || scenario == 4 ? 2 : 0;
            var changed = f.facts.stream().map(row -> new RawEvent(row.pointId(), row.pointCode(), row.buildingId(),
                    row.sourceSystem(), row.sourcePointCode(), row.sourceDeviceId(), row.rawValue(), row.eventTime(), row.receivedTime(), q, false)).toList();
            f.facts.clear(); f.facts.addAll(changed);
            Map<PolicyKey, List<PolicyInterval>> policies = scenario >= 3 ? Map.of(
                    new PolicyKey("cold", "INDICATOR_CALCULATION"), List.of(new PolicyInterval("accept", 7, null, null,
                            scenario == 5 ? Set.of() : Set.of(QualityLevel.values()[q]))),
                    new PolicyKey("electric", "INDICATOR_CALCULATION"), List.of(new PolicyInterval("accept", 7, null, null,
                            scenario == 5 ? Set.of() : Set.of(QualityLevel.values()[q])))) : Map.of();
            var resolver = QualityUsageTestFixtures.resolver(7, Map.of("INDICATOR_CALCULATION",
                    new Scenario("INDICATOR_CALCULATION", "INDICATOR_INPUT_GATE", scenario == 6 ? "DISABLED" : "ENABLED")), policies);
            f.calculator = new EerpPeriodCalculator(new CalculationPointReadService(f.scope, f.pointService, f.reader, resolver), f.nativeAggregation, f.codec);
            f.service = f.newService();
            var task = f.period(config, START, START.plusSeconds(3600), "policy-" + scenario);
            if (scenario == 6) { assertThat(task.status()).isEqualTo("FAILED"); assertThat(task.result()).isNull(); }
            else {
                assertThat(task.status()).isEqualTo("SUCCEEDED");
                assertThat(((EerpContracts.PeriodResult) task.result()).complete()).as("scenario %s", scenario)
                        .isEqualTo(scenario == 0 || scenario == 3 || scenario == 4);
            }
        }
    }

    @Test
    void multipliesJointlyChangingInputsBeforeIntegration() {
        var result = new CoolingComputationCore().calculate(START, START.plusSeconds(3600),
                List.of(sample("f0", 0, "100"), sample("f1", 1800000, "200")),
                List.of(sample("s0", 0, "7"), sample("s1", 1800000, "8")),
                List.of(sample("r0", 0, "12"), sample("r1", 1800000, "18")),
                rules(AlignmentMode.EXACT_SYNCHRONOUS, 0));
        // 显式合成cp=3.6：前半小时500kW、后半小时2000kW，共1250kWh。
        assertThat(result.complete()).isTrue();
        assertThat(result.totalKwh()).isEqualByComparingTo("1250");
        assertThat(result.totalKwh()).isNotEqualByComparingTo("1125");
    }

    @Test
    void skewBoundaryIncludesEqualityAndRejectsOneMillisecondBeyond() {
        for (long skew : List.of(999L, 1000L, 1001L)) {
            var result = new CoolingComputationCore().calculate(START.plusMillis(skew), START.plusMillis(skew + 1),
                    List.of(sample("flow", skew, "100")), List.of(sample("supply", 0, "7")),
                    List.of(sample("return", 0, "12")), rules(AlignmentMode.BOUNDED_PREVIOUS_HOLD, 1000));
            assertThat(result.complete()).as("skew=%s", skew).isEqualTo(skew <= 1000);
            if (skew > 1000) assertThat(result.issues()).anySatisfy(issue ->
                    assertThat(issue.reasonCodes()).contains("ALIGNMENT_SKEW_EXCEEDED"));
        }
    }

    private static Sample sample(String id, long millis, String value) {
        return new Sample(id, START.plusMillis(millis), new BigDecimal(value), true, "synthetic Q0");
    }

    private static Rules rules(AlignmentMode mode, long skew) {
        return new Rules("independent-v1", "synthetic reviewed", new BigDecimal("1000"), new BigDecimal("3.6"),
                BigDecimal.ZERO, new BigDecimal("40"), 3600000, skew, 3600000, false, false, mode);
    }
}
