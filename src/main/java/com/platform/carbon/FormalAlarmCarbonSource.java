package com.platform.carbon;

import com.platform.alarm.AlarmEmissionModels.Metric;
import com.platform.alarm.AlarmEmissionModels.ResolvedPeriod;
import com.platform.alarm.AlarmEmissionSource;
import com.platform.alarm.AlarmModels.EmissionSettings;
import com.platform.alarm.AlarmModels.Quota;
import com.platform.alarm.AlarmModels.Rule;
import com.platform.alarm.MonthlyQuotaSource;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/** 将生效计划和已发布核算映射到报警输入，保留来源版本及可用性。 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FormalAlarmCarbonSource implements MonthlyQuotaSource, AlarmEmissionSource {
    private final CarbonDisplayPrewarmService prewarm;
    private final CarbonDisplayTaskIdentity identity;
    private final CarbonPlanService plans;
    private final CarbonIntensityService intensity;
    private final AnnualOverviewService annual;

    @Override
    public Quota load(String buildingId, YearMonth month) {
        try {
            var reader = reader(buildingId);
            var response = plan(reader, buildingId, month);
            var actual = response.months().get(month.getMonthValue() - 1);
            if (response.plan() == null) return unavailableQuota(buildingId, month, "未找到已生效年度计划");
            if (!"AVAILABLE".equals(actual.actualStatus())) return unavailableQuota(buildingId, month, "评价月缺少有效正式核算结果");
            if (actual.quota() == null || actual.quota().signum() <= 0) return unavailableQuota(buildingId, month, "评价月缺少正数计划配额");
            return new Quota(buildingId, month, actual.actual(), actual.quota(), response.plan().versionId(),
                    actual.batchId(), actual.evidenceHash(), null);
        } catch (BusinessException exception) {
            return unavailableQuota(buildingId, month, exception.getMessage());
        }
    }

    @Override
    public boolean supports(EmissionSettings settings) {
        return switch (settings.template()) {
            case "QUOTA_PROGRESS", "INTENSITY", "MONTH_ON_MONTH", "YEAR_ON_YEAR",
                    "BASELINE", "COMPLETENESS", "YEAR_END_FORECAST" -> true;
            default -> false;
        };
    }

    @Override
    public Metric load(String buildingId, Rule rule, ResolvedPeriod period, long now) {
        var settings = rule.advanced().emission();
        try {
            return switch (settings.template()) {
                case "QUOTA_PROGRESS" -> quotaMetric(reader(buildingId), buildingId, period, now);
                case "INTENSITY" -> intensityMetric(reader(buildingId), buildingId, period, settings.period(), now);
                case "MONTH_ON_MONTH", "YEAR_ON_YEAR" -> comparison(reader(buildingId), buildingId, period, settings, now);
                case "BASELINE" -> unavailable("缺少已生效的排放基线模型及结果");
                case "COMPLETENESS" -> unavailable("缺少已确认的核算完整率或缺因子证据");
                case "YEAR_END_FORECAST" -> unavailable("缺少已确认的年末外推模型证据");
                default -> unavailable("排放模板无对应正式来源");
            };
        } catch (BusinessException exception) {
            return unavailable(exception.getMessage());
        }
    }

    private Metric quotaMetric(Reader reader, String building, ResolvedPeriod period, long now) {
        var through = period.toMonth();
        var response = plan(reader, building, through);
        if (response.plan() == null) return unavailable("未找到已生效年度计划");
        var version = response.plan().versionId();
        var closed = response.months().subList(period.fromMonth().getMonthValue() - 1, through.getMonthValue());
        var last = closed.getLast();
        if (closed.size() == 1 && period.period().equals(through.toString())) {
            if (!"AVAILABLE".equals(last.actualStatus())) return unavailable("评价月缺少有效正式核算结果");
            if (last.quota() == null || last.quota().signum() <= 0) return unavailable("评价月缺少正数计划配额");
            return metric(building, period, now, last.actual(), last.quota(), response.unit(), last.evidenceHash(),
                    last.batchId(), version, null, null, null);
        }
        if (!"COMPLETE".equals(response.status()) || response.actualCumulative() == null
                || response.annualQuota() == null || response.annualQuota().signum() <= 0) {
            return unavailable("年度配额或截至评价月的正式累计结果不完整");
        }
        // 评价期配额只汇总计划已提供的月配额，全年预算仍保留独立证据。
        var periodQuota = closed.stream().map(CarbonPlanContracts.Month::quota).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (periodQuota.signum() <= 0) return unavailable("评价期缺少正数计划配额");
        var progress = evidence(List.of(version, period.period(), closed));
        var annualBudget = evidence(List.of(version, response.annualQuota(), response.actualCumulative(), progress));
        return metric(building, period, now, response.actualCumulative(), periodQuota, response.unit(), progress,
                last.batchId(), version, null, progress, annualBudget);
    }

    private Metric intensityMetric(Reader reader, String building, ResolvedPeriod period, String kind, long now) {
        var through = period.toMonth();
        if ("MONTH".equals(kind)) {
            var actual = intensity.query(reader.actor(), reader.roles(), building, "MONTH", through.getYear(),
                    through.getMonthValue(), CarbonPlanCore.ZONE).actual();
            if (!"AVAILABLE".equals(actual.status()) || !"kgCO2e/m²".equals(actual.unit())
                    || actual.denominatorVersionId() == null) return unavailable("评价月缺少正式面积强度或面积版本");
            return metric(building, period, now, actual.value(), null, actual.unit(), actual.evidenceHash(),
                    actual.batchId(), null, actual.denominatorVersionId(), null, null);
        }
        var overview = overview(reader, building, through);
        var actual = find(overview.current(), "YTD_AREA_INTENSITY");
        if (!available(actual) || overview.current().areaParts().isEmpty()) return unavailable("评价期缺少完整累计强度或正式面积版本");
        return metric(building, period, now, actual.value(), null, actual.unit(), overview.evidenceHash(),
                actual.sources().getLast().batchId(), null, evidence(overview.current().areaParts()), null, null);
    }

    private Metric comparison(Reader reader, String building, ResolvedPeriod period, EmissionSettings settings, long now) {
        var through = period.toMonth();
        if ("YEAR".equals(settings.period())) {
            if ("MONTH_ON_MONTH".equals(settings.template())) return unavailable("年内累计尚无已确认的环比期间");
            var overview = overview(reader, building, through);
            var actual = find(overview.current(), "TOTAL_EMISSION");
            var prior = find(overview.samePeriodLastYear(), "TOTAL_EMISSION");
            if (!available(actual) || !available(prior)) return unavailable("本期或上年同截止月缺少完整正式排放");
            return metric(building, period, now, actual.value(), prior.value(), actual.unit(), overview.evidenceHash(),
                    actual.sources().getLast().batchId(), null, null, null, null);
        }
        var reference = "MONTH_ON_MONTH".equals(settings.template()) ? through.minusMonths(1) : through.minusYears(1);
        var current = plan(reader, building, through);
        var baseline = through.getYear() == reference.getYear() ? current : plan(reader, building, reference);
        var actual = current.months().get(through.getMonthValue() - 1);
        var prior = baseline.months().get(reference.getMonthValue() - 1);
        if (!"AVAILABLE".equals(actual.actualStatus()) || !"AVAILABLE".equals(prior.actualStatus())) {
            return unavailable("本期或对照月缺少有效正式排放");
        }
        return metric(building, period, now, actual.actual(), prior.actual(), current.unit(),
                evidence(List.of(actual.batchId(), actual.evidenceHash(), prior.batchId(), prior.evidenceHash())),
                actual.batchId(), null, null, null, null);
    }

    private Reader reader(String building) {
        var target = prewarm.targetForBuilding(building);
        if (target == null) throw new BusinessException(403, "ALARM_CARBON_READER_NOT_CONFIGURED", "未配置该建筑的正式排放读取身份");
        return new Reader(target.actorId(), identity.currentRoles(target.actorId()));
    }

    private CarbonPlanContracts.Response plan(Reader reader, String building, YearMonth through) {
        return plans.query(reader.actor(), reader.roles(), building, through.getYear(), through.toString());
    }

    private AnnualOverviewService.Response overview(Reader reader, String building, YearMonth through) {
        return annual.overview(reader.actor(), reader.roles(), building, through.getYear(),
                "MONTHLY_YTD", through.getMonthValue(), CarbonPlanCore.ZONE);
    }

    private static AnnualOverviewService.Metric find(AnnualOverviewService.Period period, String code) {
        return period.metrics().stream().filter(metric -> code.equals(metric.code())).findFirst().orElse(null);
    }

    private static boolean available(AnnualOverviewService.Metric metric) {
        return metric != null && "AVAILABLE".equals(metric.status()) && metric.value() != null && !metric.sources().isEmpty();
    }

    private static Metric metric(String building, ResolvedPeriod period, long now, BigDecimal value, BigDecimal reference,
            String unit, String source, String batch, String plan, String model, String progress, String budget) {
        var key = evidence(java.util.Arrays.asList(period.period(), source, batch, plan, model));
        return new Metric(true, null, value, reference, unit, source, now, key, period.period(), null,
                progress, budget, null, building, period.period(), batch, plan, model, null);
    }

    private static String evidence(Object value) {
        return CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(value)));
    }

    private static Metric unavailable(String reason) {
        return new Metric(false, reason, null, null, null, null, 0, null, null, null);
    }

    private static Quota unavailableQuota(String building, YearMonth month, String reason) {
        return new Quota(building, month, null, null, null, null, null, reason);
    }

    private record Reader(long actor, List<String> roles) { }
}
