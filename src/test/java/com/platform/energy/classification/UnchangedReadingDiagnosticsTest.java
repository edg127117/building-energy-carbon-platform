package com.platform.energy.classification;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.platform.energy.classification.ClassificationContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

class UnchangedReadingDiagnosticsTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test void reportsOriginalContiguousRangeAndSourcesWithoutChangingZeroOrPrecision() {
        var a = row("a", "P", 0, "9007199254740993.00000001", "APPROVED", null);
        var b = row("b", "P", 1, "9007199254740993.00000001", "APPROVED", null);
        var result = UnchangedReadingDiagnostics.annotate(List.of(b, a));
        assertThat(result.getFirst().factId()).isEqualTo("b");
        assertThat(result.getFirst().unchangedReading()).isNull();
        var hint = result.getLast().unchangedReading();
        assertThat(hint.intervalCount()).isEqualTo(2);
        assertThat(hint.startInclusive()).isEqualTo(START);
        assertThat(hint.endExclusive()).isEqualTo(START.plusSeconds(172800));
        assertThat(hint.counterValue().toPlainString()).isEqualTo("9007199254740993.00000001");
        assertThat(hint.sources()).extracting(ReadingDiagnosticSource::factId).containsExactly("a", "b");
        assertThat(result).allSatisfy(value -> assertThat(value.normalizedQuantity()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(a.unchangedReading()).isNull();
    }

    @Test void missingIntervalsOverlapsAndDifferentCountersDoNotBecomeOneContinuousRun() {
        var rows = List.of(row("a", "P", 0, "100", "APPROVED", null), row("b", "P", 2, "100", "APPROVED", null),
                row("c", "OTHER", 1, "100", "APPROVED", null), row("d", "P", 2, "200", "APPROVED", null));
        assertThat(UnchangedReadingDiagnostics.annotate(rows)).allSatisfy(value -> {
            assertThat(value.unchangedReading()).isNotNull();
            assertThat(value.unchangedReading().intervalCount()).isEqualTo(1);
        });
    }

    @Test void pendingRevisionDoesNotHideApprovedReadingButApprovedRevisionReplacesItsEvidence() {
        var original = row("original", "P", 0, "100", "APPROVED", null);
        var pending = row("revision", "P", 0, "200", "PENDING_REVIEW", "original");
        var before = UnchangedReadingDiagnostics.annotate(List.of(original, pending));
        assertThat(before.getFirst().unchangedReading()).isNotNull();
        assertThat(before.getLast().unchangedReading()).isNull();
        var approved = row("revision", "P", 0, "200", "APPROVED", "original");
        var after = UnchangedReadingDiagnostics.annotate(List.of(original, approved));
        assertThat(after.getFirst().unchangedReading()).isNull();
        assertThat(after.getLast().unchangedReading().sources()).extracting(ReadingDiagnosticSource::factId).containsExactly("revision");
    }

    @Test void dateOnlyPendingConfirmationAndPeriodTotalsAreNotExactCumulativeDiagnostics() {
        var base = row("a", "P", 0, "100", "APPROVED", null);
        var r = base.reading();
        var changed = List.of(
                new Reading(r.pointId(), r.startInclusive(), r.endExclusive(), r.energyItemCode(), r.unit(), "CUMULATIVE", r.startReading(), r.endReading(), null, BigDecimal.ONE, false, "PENDING_EXPERT", "pending", "EXACT", null),
                new Reading(r.pointId(), r.startInclusive(), r.endExclusive(), r.energyItemCode(), r.unit(), "CUMULATIVE", r.startReading(), r.endReading(), null, BigDecimal.ONE, false, "CONFIRMED", "date only", "DATE_ONLY", null),
                new Reading(r.pointId(), r.startInclusive(), r.endExclusive(), r.energyItemCode(), r.unit(), "PERIOD_TOTAL", null, null, BigDecimal.ZERO, BigDecimal.ONE, false, "CONFIRMED", "period total", "EXACT", null));
        for (var reading : changed) {
            var value = new ReadingView("test", "batch", reading, BigDecimal.ZERO, 1, "APPROVED", null);
            assertThat(UnchangedReadingDiagnostics.annotate(List.of(value)).getFirst().unchangedReading()).isNull();
        }
    }

    @Test void multipliersKeepIndependentRunsWithoutChangingTheirStatus() {
        var first = row("a", "P", 0, "100", "APPROVED", null);
        var next = row("b", "P", 1, "100", "APPROVED", null);
        var r = next.reading();
        var scaled = new Reading(r.pointId(), r.startInclusive(), r.endExclusive(), r.energyItemCode(), r.unit(), r.semantics(),
                r.startReading(), r.endReading(), null, BigDecimal.TEN, false, r.status(), r.sourceReference(), r.timePrecision(), null);
        var result = UnchangedReadingDiagnostics.annotate(List.of(first, new ReadingView("b", "batch", scaled, BigDecimal.ZERO, 1, "APPROVED", null)));
        assertThat(result).allSatisfy(v -> {
            assertThat(v.unchangedReading().intervalCount()).isEqualTo(1);
            assertThat(v.batchStatus()).isEqualTo("APPROVED");
        });
    }

    @Test void differentUnitsAndScalingBasisNeverMerge() {
        var first = row("a", "P", 0, "100", "APPROVED", null);
        for (boolean differentUnit : List.of(true, false)) {
            var r = row("b", "P", 1, "100", "APPROVED", null).reading();
            var changed = new Reading(r.pointId(), r.startInclusive(), r.endExclusive(), r.energyItemCode(),
                    differentUnit ? "MWh" : "kWh", r.semantics(), r.startReading(), r.endReading(), null,
                    BigDecimal.ONE, !differentUnit, r.status(), r.sourceReference(), r.timePrecision(), null);
            var result = UnchangedReadingDiagnostics.annotate(List.of(first,
                    new ReadingView("b", "batch-b", changed, BigDecimal.ZERO, 1, "APPROVED", null)));
            assertThat(result).allSatisfy(v -> assertThat(v.unchangedReading().intervalCount()).isEqualTo(1));
        }
    }

    @Test void boundedLongRunKeepsOneEvidenceListRatherThanQuadraticCopies() {
        var rows = new ArrayList<ReadingView>();
        for (int i = 0; i < 5000; i++) rows.add(row("fact-" + i, "P", i, "100", "APPROVED", null));
        var result = UnchangedReadingDiagnostics.annotate(rows);
        assertThat(result.stream().filter(r -> r.unchangedReading() != null)).hasSize(1);
        assertThat(result.getFirst().unchangedReading().intervalCount()).isEqualTo(5000);
        assertThat(result.getFirst().unchangedReading().sources()).hasSize(5000);
    }

    private static ReadingView row(String id, String point, int day, String value, String batchStatus, String supersedes) {
        var r = new Reading(point, START.plusSeconds(day * 86400L), START.plusSeconds((day + 1) * 86400L),
                "ELECTRICITY", "kWh", "CUMULATIVE", new BigDecimal(value), new BigDecimal(value), null,
                BigDecimal.ONE, false, "CONFIRMED", "ledger-" + id, "EXACT", supersedes);
        return new ReadingView(id, "batch-" + id, r, BigDecimal.ZERO, 1, batchStatus, null);
    }
}
