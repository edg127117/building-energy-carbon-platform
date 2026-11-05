package com.platform.carbon;

import com.platform.alarm.AlarmEmissionEvaluator;
import com.platform.alarm.AlarmModels.Advanced;
import com.platform.alarm.AlarmModels.EmissionSettings;
import com.platform.alarm.AlarmModels.Rule;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FormalAlarmCarbonSourceTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 10);
    private static final List<String> ROLES = List.of("BUILDING_OWNER");
    private final CarbonDisplayPrewarmService prewarm = mock(CarbonDisplayPrewarmService.class);
    private final CarbonDisplayTaskIdentity identity = mock(CarbonDisplayTaskIdentity.class);
    private final CarbonPlanService plans = mock(CarbonPlanService.class);
    private final FormalAlarmCarbonSource source = new FormalAlarmCarbonSource(
            prewarm, identity, plans, mock(CarbonIntensityService.class), mock(AnnualOverviewService.class));

    @BeforeEach
    void reader() {
        when(prewarm.targetForBuilding("B")).thenReturn(new CarbonDisplayQueryProperties.PrewarmTarget("CXG", 42));
        when(identity.currentRoles(42)).thenReturn(ROLES);
    }

    @Test
    void legacyQuotaUsesExactPublishedMonthEvenWhenOtherMonthsAreIncomplete() {
        when(plans.query(42, ROLES, "B", 2026, "2026-10")).thenReturn(response("INCOMPLETE_ACTUAL", null));
        var quota = source.load("B", MONTH);
        assertThat(quota.unavailableReason()).isNull();
        assertThat(quota.actual()).isEqualByComparingTo("12.349152");
        assertThat(quota.quota()).isEqualByComparingTo("10.50");
        assertThat(quota.planVersion()).isEqualTo("plan-v2");
        assertThat(quota.batchId()).isEqualTo("batch-10");
        assertThat(quota.evidenceHash()).isEqualTo("hash-10");
    }

    @Test
    void annualQuotaSeparatesElapsedPlanFromAnnualBudgetAndKeepsSourceKeyStable() {
        when(plans.query(42, ROLES, "B", 2026, "2026-10")).thenReturn(response("COMPLETE", new BigDecimal("120")));
        var settings = new EmissionSettings("QUOTA_PROGRESS", "YEAR", BigDecimal.valueOf(100), BigDecimal.valueOf(95), null, null, null);
        var rule = new Rule("quota", "EMISSION", "quota", "WARNING", "Q0", null, null,
                true, 1, 1, null, null, 1, 0, 0, "ALL", "EMISSION", List.of(),
                new Advanced(null, null, null, null, null, settings));
        long now = Instant.parse("2026-11-05T01:00:00Z").toEpochMilli();
        var period = AlarmEmissionEvaluator.resolvePeriod(settings, now);
        var metric = source.load("B", rule, period, now);
        assertThat(metric.available()).isTrue();
        assertThat(metric.reference()).isEqualByComparingTo("105.00");
        assertThat(metric.quotaProgressEvidence()).isNotBlank().isNotEqualTo(metric.annualBudgetProgressEvidence());
        assertThat(source.load("B", rule, period, now + 1000).observationKey()).isEqualTo(metric.observationKey());
    }

    @Test
    void unavailableReadersCannotQueryAnotherBuildingOrReusePriorResults() {
        assertThat(source.load("other", MONTH).unavailableReason()).contains("读取身份");
        when(identity.currentRoles(42)).thenThrow(new BusinessException(403, "REVOKED", "账号已撤权"));
        assertThat(source.load("B", MONTH).unavailableReason()).isEqualTo("账号已撤权");
        verifyNoInteractions(plans);
    }

    private CarbonPlanContracts.Response response(String status, BigDecimal cumulative) {
        var version = new CarbonPlanContracts.Version("plan-v2", "B", 2026, 2, 1, "ACTIVE", 42,
                null, null, null, null, null);
        var months = IntStream.rangeClosed(1, 12).mapToObj(month -> new CarbonPlanContracts.Month(month,
                BigDecimal.TEN, new BigDecimal("10.50"), new BigDecimal("12.349152"), "AVAILABLE",
                "batch-" + month, "hash-" + month, null, null, null)).toList();
        return new CarbonPlanContracts.Response("B", 2026, "Asia/Shanghai", "tCO2e", status, "2026-10", "2026-10",
                List.of(), version, null, null, new BigDecimal("126"), cumulative, null, months);
    }
}
