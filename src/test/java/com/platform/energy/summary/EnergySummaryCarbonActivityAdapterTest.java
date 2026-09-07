package com.platform.energy.summary;

import com.platform.carbon.CarbonModels.PeriodType;
import com.platform.carbon.CarbonEvidence;
import com.platform.carbon.CarbonCalculationCore;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryGroupView;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnergySummaryCarbonActivityAdapterTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant MONTH_END = Instant.parse("2026-02-01T00:00:00Z");

    private final EnergyBoundarySummaryService summaryService = mock(EnergyBoundarySummaryService.class);
    private final EnergyPeriodSnapshotReader snapshotReader = mock(EnergyPeriodSnapshotReader.class);
    private final EnergySummaryCarbonActivityAdapter adapter =
            new EnergySummaryCarbonActivityAdapter(summaryService, snapshotReader);

    @Test
    void readsOnlyAuthoritativeGrossInboundQuantityAndPinsVisibleSnapshotEvidence() {
        String evidence = "e".repeat(64);
        when(summaryService.query(org.mockito.ArgumentMatchers.eq(0L), any(), any()))
                .thenReturn(summary(Map.of("KWH", new BigDecimal("1200")), evidence));
        when(snapshotReader.listVisibleSnapshots("BLD001", "MONTH", START, END, 501))
                .thenReturn(List.of(snapshot(evidence)));

        var result = adapter.read("BLD001", PeriodType.YEAR, START, END, 500);

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.energyItemCode()).isEqualTo("ELECTRICITY");
            assertThat(value.quantity()).isEqualByComparingTo("1200");
            assertThat(value.unitCode()).isEqualTo("KWH");
            assertThat(value.evidenceHash()).hasSize(64);
            assertThat(value.periodType()).isEqualTo(PeriodType.MONTH);
            assertThat(value.endExclusive()).isEqualTo(MONTH_END);
            var trace = value.traceEvidence();
            assertThat(trace.at("/sources/0/snapshotId").asText()).isEqualTo("SNAP001");
            assertThat(trace.at("/sources/0/activityWatermark").asText()).isEqualTo(END.toString());
            assertThat(trace.at("/sources/0/evidence/qualityPolicyVersions/0").asText()).isEqualTo("QUALITY-V1");
            assertThat(trace.at("/relationVersionIds/0").asText()).isEqualTo("REL-V1");
            assertThat(trace.at("/summaryPolicyVersionIds/0").asText()).isEqualTo("SUMMARY-V1");
            assertThat(CarbonCalculationCore.sha256(CarbonEvidence.canonical(trace))).isEqualTo(value.evidenceHash());
            ((com.fasterxml.jackson.databind.node.ObjectNode) trace).remove("sources");
            assertThat(value.traceEvidence().has("sources")).isTrue();
        });
    }

    @Test
    void rejectsAmbiguousAuthoritativeUnitsInsteadOfGuessingAConversion() {
        String evidence = "e".repeat(64);
        when(summaryService.query(org.mockito.ArgumentMatchers.eq(0L), any(), any()))
                .thenReturn(summary(Map.of("KWH", BigDecimal.ONE, "MWH", BigDecimal.ONE), evidence));
        when(snapshotReader.listVisibleSnapshots("BLD001", "MONTH", START, END, 501))
                .thenReturn(List.of(snapshot(evidence)));

        assertThatThrownBy(() -> adapter.read("BLD001", PeriodType.YEAR, START, END, 500))
                .hasMessageContaining("只有一个权威活动量单位");
    }

    @Test
    void rejectsPartialSnapshotEvidenceAfterConcurrentReplacement() {
        when(summaryService.query(org.mockito.ArgumentMatchers.eq(0L), any(), any()))
                .thenReturn(summary(Map.of("KWH", BigDecimal.ONE), "still-visible", "replaced-hash"));
        when(snapshotReader.listVisibleSnapshots("BLD001", "MONTH", START, END, 501))
                .thenReturn(List.of(snapshot("still-visible")));
        assertThatThrownBy(() -> adapter.read("BLD001", PeriodType.YEAR, START, END, 500))
                .hasMessageContaining("版本不一致");
    }

    private static SummaryQueryView summary(Map<String, BigDecimal> quantities, String... evidence) {
        SummaryGroupView group = new SummaryGroupView(
                Map.of("ENERGY_ITEM", "ELECTRICITY", "PERIOD", START + "/" + MONTH_END),
                quantities, quantities, Map.of(), quantities, Map.of("CALORIFIC", BigDecimal.TEN),
                Map.of(), Map.of(), Map.of(), BigDecimal.ONE, 0,
                "LOCKED_COMPLETE", "COMPLETE", List.of("REL-V1"), List.of(),
                List.of("SUMMARY-V1"), List.of(evidence), "FORMAL");
        return new SummaryQueryView("BLD001", "MONTH", START, END,
                List.of("ENERGY_ITEM", "PERIOD"), 1, List.of(group));
    }

    private static PeriodSnapshot snapshot(String evidence) {
        return new PeriodSnapshot("SNAP001", "r".repeat(64), "PROJ001", "BLD001",
                "POINT001", "MONTH", START, MONTH_END, "Asia/Shanghai", "PERIOD-V1", 1,
                "LOCKED_COMPLETE", "FORMAL", "ELECTRICITY", new BigDecimal("1200"),
                "KWH", BigDecimal.ZERO, "TCE", BigDecimal.ONE, "", "",
                "{\"qualityPolicyVersions\":[\"QUALITY-V1\"]}", evidence,
                "{}", END, null, null, 1L, LocalDateTime.of(2027, 1, 2, 0, 0));
    }
}
