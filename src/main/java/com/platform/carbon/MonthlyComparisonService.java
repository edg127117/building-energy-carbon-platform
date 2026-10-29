package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.platform.energy.reporting.ReportingContracts.Batch;
import com.platform.energy.reporting.ReportingContracts.PreparedRow;
import com.platform.energy.reporting.ReportingService;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.platform.carbon.CarbonModels.PeriodType;
import static com.platform.carbon.CarbonModels.SummaryMetric;

/** 比较已经封账/发布的月度结果；不读取原始表底、不运行折标或碳因子公式。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyComparisonService {
    private static final List<String> CODES = List.of("ELECTRICITY_KWH", "ELECTRICITY_TCE", "TOTAL_EMISSION", "MONTH_AREA_INTENSITY");
    private static final List<String> UNITS = List.of("kWh", "tce", "tCO2e", "kgCO2e/m²");
    private final CarbonAuthorization authorization;
    private final CarbonCalculationRepository carbon;
    private final ReportingService reporting;

    public record Value(String metricCode, String unit,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value,
                        String status, String sourceBatchId, String evidenceHash, String referenceVersionId) { }
    public record Period(LocalDate month, Instant startInclusive, Instant endExclusive, List<Value> metrics) { }
    public record Change(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentValue,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseValue,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal absoluteChange,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal percentageChange,
                         String status) { }
    public record Comparison(String metricCode, String unit, Change monthOnMonth, Change yearOnYear) { }
    public record Response(String buildingId, String timezoneId, Period current, Period previousMonth,
                           Period sameMonthLastYear, List<Comparison> comparisons) { }

    public Response compare(long user, Collection<String> roles, String building, LocalDate month, String timezoneId) {
        if (building == null || building.isBlank() || building.length() > 32 || month == null
                || month.getDayOfMonth() != 1 || month.getYear() < 2000 || month.getYear() > 2200) {
            throw invalid("建筑标识及2000至2200年内的月首日期必填");
        }
        ZoneId zone;
        try { zone = ZoneId.of(timezoneId); }
        catch (DateTimeException | NullPointerException error) { throw invalid("统计时区无效"); }
        authorization.requireReader(user, roles, building);
        Period current = period(user, roles, building, month, zone);
        Period previous = period(user, roles, building, month.minusMonths(1), zone);
        Period lastYear = period(user, roles, building, month.minusYears(1), zone);
        var comparisons = java.util.stream.IntStream.range(0, CODES.size()).mapToObj(i ->
                new Comparison(CODES.get(i), UNITS.get(i), change(current.metrics().get(i), previous.metrics().get(i)),
                        change(current.metrics().get(i), lastYear.metrics().get(i)))).toList();
        return new Response(building, zone.getId(), current, previous, lastYear, comparisons);
    }

    private Period period(long user, Collection<String> roles, String building, LocalDate month, ZoneId zone) {
        Instant start = month.atStartOfDay(zone).toInstant();
        Instant end = month.plusMonths(1).atStartOfDay(zone).toInstant();
        List<Value> electricity = electricity(reporting.sealedMonth(user, roles, building, month), zone);
        var batch = carbon.findCurrentFormal(building, PeriodType.MONTH, start, end, zone.getId());
        List<Value> emissions;
        if (batch == null) {
            emissions = List.of(unavailable(2, "NO_COMPLETE_CARBON_RESULT", null), unavailable(3, "NO_COMPLETE_CARBON_RESULT", null));
        } else {
            var summaries = carbon.listSummaries(batch.batchId());
            emissions = List.of(summary(2, summaries, batch.batchId(), batch.roundingPolicyVersionId()),
                    summary(3, summaries, batch.batchId(), null));
        }
        return new Period(month, start, end, List.of(electricity.get(0), electricity.get(1), emissions.get(0), emissions.get(1)));
    }

    private List<Value> electricity(List<Batch> batches, ZoneId zone) {
        if (batches.size() != 1) {
            String status = batches.isEmpty() ? "NO_SEALED_STATISTICS" : "CONFLICTING_SEALED_STATISTICS";
            return List.of(unavailable(0, status, null), unavailable(1, status, null));
        }
        Batch batch = batches.getFirst();
        if (batch.snapshot() == null || !"COMPLETE".equals(batch.snapshot().nativeStatus())) {
            return List.of(unavailable(0, "INCOMPLETE_NATIVE", batch.batchId()), unavailable(1, "INCOMPLETE_NATIVE", batch.batchId()));
        }
        List<PreparedRow> primary = batch.snapshot().rows().stream().filter(row ->
                "ELECTRICITY".equals(row.source().configuration().energyItemCode())
                        && "PRIMARY".equals(row.source().configuration().purpose())
                        && "WHOLE_BUILDING".equals(row.source().configuration().coverage())).toList();
        if (primary.size() != 1) {
            String status = primary.isEmpty() ? "NO_ELECTRICITY_PRIMARY_SOURCE" : "CONFLICTING_ELECTRICITY_PRIMARY_SOURCES";
            return List.of(unavailable(0, status, batch.batchId()), unavailable(1, status, batch.batchId()));
        }
        PreparedRow row = primary.getFirst();
        if (!zone.getId().equals(row.source().configuration().timezoneId())) {
            return List.of(unavailable(0, "TIMEZONE_MISMATCH", batch.batchId()), unavailable(1, "TIMEZONE_MISMATCH", batch.batchId()));
        }
        var unit = row.unitEvidence();
        Value quantity;
        // 使用封账时冻结的比例兼容kWh/MWh来源替换，不能用今天的单位目录改写历史。
        if (unit == null || !row.unitVersionId().equals(unit.versionId()) || !row.nativeUnit().equals(unit.unitCode())
                || !"KWH".equals(unit.canonicalUnitCode()) || unit.scaleFactor() == null || unit.scaleFactor().signum() <= 0
                || !Set.of("IDENTITY", "FIXED_SCALE").contains(unit.conversionType()) || row.nativeQuantity() == null) {
            quantity = unavailable(0, "UNIT_EVIDENCE_UNAVAILABLE", batch.batchId());
        } else {
            quantity = available(0, row.nativeQuantity().multiply(unit.scaleFactor(), MathContext.DECIMAL128),
                    batch.batchId(), batch.snapshot().evidenceHash(), unit.versionId());
        }
        Value tce = "COMPLETE".equals(row.tceStatus()) && row.tce() != null
                ? available(1, row.tce(), batch.batchId(), batch.snapshot().evidenceHash(), null)
                : unavailable(1, "INCOMPLETE_TCE", batch.batchId());
        return List.of(quantity, tce);
    }

    private Value summary(int index, List<SummaryMetric> summaries, String batchId, String versionId) {
        var matches = summaries.stream().filter(v -> CODES.get(index).equals(v.metricCode()) && "TOTAL".equals(v.dimensionCode())).toList();
        if (matches.size() != 1) return unavailable(index, matches.isEmpty() ? "MISSING_METRIC" : "CONFLICTING_METRICS", batchId);
        var metric = matches.getFirst();
        if (!UNITS.get(index).equals(metric.unitCode())) return unavailable(index, "UNIT_MISMATCH", batchId);
        if (metric.finalValue() == null || metric.unavailableReason() != null) return unavailable(index, "METRIC_UNAVAILABLE", batchId);
        return available(index, metric.finalValue(), batchId, metric.evidenceHash(),
                index == 3 ? metric.denominatorVersionId() : versionId);
    }

    static Change change(Value current, Value base) {
        if (!"AVAILABLE".equals(current.status())) return new Change(current.value(), base.value(), null, null, "CURRENT_UNAVAILABLE");
        if (!"AVAILABLE".equals(base.status())) return new Change(current.value(), base.value(), null, null, "BASE_UNAVAILABLE");
        BigDecimal delta = current.value().subtract(base.value());
        // 基期为0时仍可展示绝对变化，百分比没有有限定义，不能伪造0%或无穷大。
        if (base.value().signum() == 0) return new Change(current.value(), base.value(), delta, null, "BASE_ZERO");
        BigDecimal percentage = delta.multiply(BigDecimal.valueOf(100)).divide(base.value(), 6, RoundingMode.HALF_UP);
        return new Change(current.value(), base.value(), delta, percentage, "OK");
    }

    private static Value available(int i, BigDecimal value, String batch, String hash, String version) {
        return new Value(CODES.get(i), UNITS.get(i), value, "AVAILABLE", batch, hash, version);
    }
    private static Value unavailable(int i, String status, String batch) {
        return new Value(CODES.get(i), UNITS.get(i), null, status, batch, null, null);
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(400, "MONTHLY_COMPARISON_INVALID", message);
    }
}
