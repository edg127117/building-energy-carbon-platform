package com.platform.carbon;

import com.platform.energy.catalog.EnergyCatalogLookup.ApprovedUnit;
import com.platform.energy.reporting.ReportingService;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static com.platform.carbon.CarbonModels.*;
import static com.platform.carbon.MonthlyComparisonService.*;
import static com.platform.energy.reporting.ReportingContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonthlyComparisonServiceTest {
    private static final String BUILDING = "REAL_REPLACEMENT_BUILDING";
    private static final LocalDate MONTH = LocalDate.of(2024, 1, 1);
    private static final Set<String> ROLES = Set.of("BUILDING_OWNER");
    private final CarbonAuthorization authorization = mock(CarbonAuthorization.class);
    private final CarbonCalculationRepository carbon = mock(CarbonCalculationRepository.class);
    private final ReportingService reporting = mock(ReportingService.class);
    private final MonthlyComparisonService service = new MonthlyComparisonService(authorization, carbon, reporting);

    @BeforeEach void missingByDefault() {
        when(reporting.sealedMonth(anyLong(), anyCollection(), anyString(), any())).thenReturn(List.of());
    }

    @Test void comparesFrozenResultsAcrossYearBoundaryWithDecimalPercentageAndSourceReferences() {
        month(MONTH, "1000", "1.2", "10", ".2", "Asia/Shanghai", "KWH", "1");
        month(MONTH.minusMonths(1), "800", ".98", "8", ".16", "Asia/Shanghai", "KWH", "1");
        month(MONTH.minusYears(1), "500", ".5", "5", ".1", "Asia/Shanghai", "KWH", "1");
        var result = service.compare(7, ROLES, BUILDING, MONTH, "Asia/Shanghai");
        assertThat(result.previousMonth().month()).isEqualTo(LocalDate.of(2023, 12, 1));
        assertThat(result.sameMonthLastYear().month()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(result.current().metrics()).allSatisfy(v -> assertThat(v.status()).isEqualTo("AVAILABLE"));
        var quantity = result.comparisons().getFirst();
        assertThat(quantity.monthOnMonth().absoluteChange()).isEqualByComparingTo("200");
        assertThat(quantity.monthOnMonth().percentageChange()).isEqualByComparingTo("25.000000");
        assertThat(quantity.yearOnYear().percentageChange()).isEqualByComparingTo("100.000000");
        assertThat(result.current().metrics().get(0).sourceBatchId()).isEqualTo("stats-2024-01-01");
        assertThat(result.current().metrics().get(0).referenceVersionId()).isEqualTo("frozen-unit-v1");
        assertThat(result.current().metrics().get(3).referenceVersionId()).isEqualTo("frozen-area-v1");
        verify(authorization).requireReader(7, ROLES, BUILDING);
    }

    @Test void missingPeriodsAreNotZeroAndZeroBaseKeepsAbsoluteChangeButNoPercentage() {
        month(MONTH, "10", "0", "0", "0", "Asia/Shanghai", "KWH", "1");
        month(MONTH.minusMonths(1), "0", "0", "0", "0", "Asia/Shanghai", "KWH", "1");
        var result = service.compare(7, ROLES, BUILDING, MONTH, "Asia/Shanghai");
        assertThat(result.comparisons()).allSatisfy(c -> {
            assertThat(c.monthOnMonth().status()).isEqualTo("BASE_ZERO");
            assertThat(c.monthOnMonth().percentageChange()).isNull();
            assertThat(c.yearOnYear().status()).isEqualTo("BASE_UNAVAILABLE");
        });
        assertThat(result.comparisons().getFirst().monthOnMonth().absoluteChange()).isEqualByComparingTo("10");
        assertThat(result.sameMonthLastYear().metrics()).allSatisfy(v -> assertThat(v.value()).isNull());
    }

    @Test void realBillInMwhUsesItsFrozenUnitScaleAndUnavailableTceStaysNull() {
        month(MONTH, "1.234", null, "7", ".1", "Asia/Shanghai", "MWH", "1000");
        var result = service.compare(7, ROLES, BUILDING, MONTH, "Asia/Shanghai");
        assertThat(result.current().metrics().get(0).value()).isEqualByComparingTo("1234");
        assertThat(result.current().metrics().get(1).value()).isNull();
        assertThat(result.current().metrics().get(1).status()).isEqualTo("INCOMPLETE_TCE");
        assertThat(result.comparisons().get(1).monthOnMonth().status()).isEqualTo("CURRENT_UNAVAILABLE");
    }

    @Test void duplicateSealedStatisticsAndDuplicateCarbonMetricsAreNotSilentlyAdded() {
        var b = bill(MONTH, "1", "1", "Asia/Shanghai", "KWH", "1");
        when(reporting.sealedMonth(7, ROLES, BUILDING, MONTH)).thenReturn(List.of(b, b));
        var batch = carbonMonth(MONTH, "Asia/Shanghai");
        var summary = new SummaryMetric("TOTAL_EMISSION", "TOTAL", BigDecimal.ONE, BigDecimal.ONE, "tCO2e", null, null, "hash");
        when(carbon.listSummaries(batch.batchId())).thenReturn(List.of(summary, summary));
        var result = service.compare(7, ROLES, BUILDING, MONTH, "Asia/Shanghai");
        assertThat(result.current().metrics().get(0).status()).isEqualTo("CONFLICTING_SEALED_STATISTICS");
        assertThat(result.current().metrics().get(2).status()).isEqualTo("CONFLICTING_METRICS");
        assertThat(result.current().metrics()).allSatisfy(v -> assertThat(v.value()).isNull());
    }

    @Test void rejectsCrossBuildingBeforeReadingAnyMonthlyResultAndRejectsInvalidDateOrZone() {
        doThrow(new BusinessException(403, "禁止跨建筑")).when(authorization).requireReader(7, ROLES, BUILDING);
        assertThatThrownBy(() -> service.compare(7, ROLES, BUILDING, MONTH, "Asia/Shanghai")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reporting, carbon);
        assertThatThrownBy(() -> service.compare(7, ROLES, BUILDING, MONTH.plusDays(1), "Asia/Shanghai")).hasMessageContaining("月首");
        assertThatThrownBy(() -> service.compare(7, ROLES, BUILDING, MONTH, "invalid/zone")).hasMessageContaining("时区");
    }

    @Test void usesRequestedCivilMonthIncludingDaylightSavingBoundary() {
        var month = LocalDate.of(2024, 3, 1);
        service.compare(7, ROLES, BUILDING, month, "America/New_York");
        verify(carbon).findCurrentFormal(BUILDING, PeriodType.MONTH, Instant.parse("2024-03-01T05:00:00Z"),
                Instant.parse("2024-04-01T04:00:00Z"), "America/New_York");
    }

    @Test void negativeChangeIsReturnedAndPercentageHasSixDecimalPlaces() {
        Value current = new Value("ELECTRICITY_KWH", "kWh", new BigDecimal("2"), "AVAILABLE", "a", "h", "u");
        Value base = new Value("ELECTRICITY_KWH", "kWh", new BigDecimal("3"), "AVAILABLE", "b", "h", "u");
        assertThat(change(current, base).percentageChange()).isEqualTo(new BigDecimal("-33.333333"));
    }

    private void month(LocalDate month, String quantity, String tce, String emission, String intensity,
                       String timezone, String unit, String scale) {
        when(reporting.sealedMonth(7, ROLES, BUILDING, month)).thenReturn(List.of(bill(month, quantity, tce, timezone, unit, scale)));
        var batch = carbonMonth(month, timezone);
        when(carbon.listSummaries(batch.batchId())).thenReturn(List.of(
                new SummaryMetric("TOTAL_EMISSION", "TOTAL", new BigDecimal(emission), new BigDecimal(emission), "tCO2e", null, null, "carbon-hash"),
                new SummaryMetric("MONTH_AREA_INTENSITY", "TOTAL", new BigDecimal(intensity), new BigDecimal(intensity), "kgCO2e/m²", "frozen-area-v1", null, "area-hash")));
    }

    private CalculationBatch carbonMonth(LocalDate month, String timezone) {
        var batch = mock(CalculationBatch.class);
        when(batch.batchId()).thenReturn("carbon-" + month);
        when(batch.roundingPolicyVersionId()).thenReturn("rounding-v1");
        var zone = ZoneId.of(timezone);
        when(carbon.findCurrentFormal(BUILDING, PeriodType.MONTH, month.atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atStartOfDay(zone).toInstant(), timezone)).thenReturn(batch);
        return batch;
    }

    private Batch bill(LocalDate month, String quantity, String tce, String timezone, String unit, String scale) {
        var config = new SourceConfig("真实账单也使用相同封账契约", "ELECTRICITY", unit, "BILL", "PURCHASED_ELECTRICITY", "PRIMARY",
                "WHOLE_BUILDING", "REPLACES_METER_TOTAL", timezone, month, null, "来源证据", "原因", null, null, null, null);
        var source = new Source("source", 2, "APPROVED", 1, 2L, config);
        var frozenUnit = new ApprovedUnit("unit", unit, "frozen-unit-v1", 1, unit, "ENERGY", "KWH", new BigDecimal(scale),
                "MWH".equals(unit) ? "FIXED_SCALE" : "IDENTITY", null, "STANDARD", "冻结单位", month.atStartOfDay(), null);
        var row = new PreparedRow(null, source, new BigDecimal(quantity), unit, "energy-v1", "frozen-unit-v1", frozenUnit,
                tce == null ? null : new BigDecimal(tce), tce == null ? "NOT_CALCULATED" : "COMPLETE", null);
        return new Batch("stats-" + month, BUILDING, month, "SEALED", 3, 1, 2L, null, List.of(),
                new Snapshot("ENERGY_BILL_MONTH_V1", "COMPLETE", tce == null ? "INCOMPLETE" : "COMPLETE", List.of(row), "statistics-hash"));
    }
}
