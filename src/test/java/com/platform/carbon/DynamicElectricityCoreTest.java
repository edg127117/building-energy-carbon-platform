package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DynamicElectricityCoreTest {
    private static final Instant FROM = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(3600);

    @Test void weightsByElectricityAfterMatchingEachSource() {
        var value = DynamicElectricityCore.calculate(List.of(input("A", "90"), input("B", "10")),
                List.of(factor("A", FROM, TO, "0.2"), factor("B", FROM, TO, "0.8")), FROM, TO);
        assertThat(value.quantity()).isEqualByComparingTo("100");
        assertThat(value.emissionKgCo2()).isEqualByComparingTo("26");
        assertThat(value.factor()).isEqualByComparingTo("0.26");
    }

    @Test void timeAveragesFinerInputAndRecordsEstimation() {
        Instant half = FROM.plusSeconds(1800);
        var value = DynamicElectricityCore.calculate(List.of(input("A", "10")),
                List.of(factor("*", FROM, half, "0.2"), factor("*", half, TO, "0.8")), FROM, TO);
        assertThat(value.emissionKgCo2()).isEqualByComparingTo("5");
        assertThat(value.timeAveraged()).isTrue();
    }

    @Test void rejectsGapsOverlapsAndSpecificGapWithoutCommonFallback() {
        var gap = List.of(factor("A", FROM, FROM.plusSeconds(900), "0.2"), factor("*", FROM, TO, "0.5"));
        assertThatThrownBy(() -> DynamicElectricityCore.calculate(List.of(input("A", "10")), gap, FROM, TO))
                .hasMessageContaining("完整覆盖");
        var overlap = List.of(factor("*", FROM, TO, "0.2"), factor("*", FROM.plusSeconds(900), TO, "0.5"));
        assertThatThrownBy(() -> DynamicElectricityCore.calculate(List.of(input("A", "10")), overlap, FROM, TO))
                .hasMessageContaining("重叠");
    }

    @Test void zeroElectricityHasZeroEmissionAndUndefinedFactor() {
        var value = DynamicElectricityCore.calculate(List.of(input("A", "0")), List.of(factor("*", FROM, TO, "0.5")), FROM, TO);
        assertThat(value.emissionKgCo2()).isZero();
        assertThat(value.factor()).isNull();
        assertThatThrownBy(() -> DynamicElectricityCore.calculate(List.of(input("A", "0")), List.of(), FROM, TO))
                .hasMessageContaining("完整覆盖");
    }

    @Test void rejectsDuplicateMeterAndInvalidAlignedWindows() {
        assertThatThrownBy(() -> DynamicElectricityCore.calculate(List.of(input("A", "1"), input("A", "1")),
                List.of(factor("*", FROM, TO, "0.5")), FROM, TO)).hasMessageContaining("重复");
        assertThatThrownBy(() -> DynamicElectricityService.split(FROM.plusSeconds(1), TO, "HOUR"))
                .hasMessageContaining("对齐");
        assertThat(DynamicElectricityService.split(Instant.parse("2024-02-01T00:00:00Z").minusSeconds(28800),
                Instant.parse("2024-03-01T00:00:00Z").minusSeconds(28800), "DAY")).hasSize(29);
    }

    private static DynamicElectricityInputReader.Contribution input(String point, String amount) {
        return new DynamicElectricityInputReader.Contribution(point, Set.of(point), new BigDecimal(amount), List.of("source-" + point));
    }
    private static DynamicElectricityCore.Factor factor(String point, Instant from, Instant to, String value) {
        return new DynamicElectricityCore.Factor(UUID.randomUUID().toString(), point, from, to, new BigDecimal(value),
                "SIMULATED", "isolated-fixture", "420000", "NO_EXTERNAL_HYDRO");
    }
}
