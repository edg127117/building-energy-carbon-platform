package com.platform.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.alarm.AlarmEmissionModels.*;
import static com.platform.alarm.AlarmModels.*;

/** 只将已确认来源指标映射为报警候选；此处不计算排放量、配额或基线。 */
@Component
public class AlarmEmissionEvaluator {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final List<AlarmEmissionSource> sources;
    private final AlarmTemplateCatalog templates;
    private final ObjectMapper json;

    @Autowired
    public AlarmEmissionEvaluator(List<AlarmEmissionSource> sources, ObjectMapper json) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.templates = new AlarmTemplateCatalog(this.sources.stream().filter(AlarmTemplateProvider.class::isInstance)
                .map(AlarmTemplateProvider.class::cast).toList());
        this.json = json;
    }

    public static void validate(RuleRequest request) {
        if (request == null || !"EMISSION".equals(request.type())) return;
        EmissionSettings settings = request.advanced() == null ? null : request.advanced().emission();
        if (settings == null || settings.template() == null || !Set.of("QUOTA_PROGRESS", "INTENSITY", "MONTH_ON_MONTH", "YEAR_ON_YEAR",
                "BASELINE", "COMPLETENESS", "YEAR_END_FORECAST").contains(settings.template())) {
            throw new BusinessException("排放规则模板无效");
        }
        if (settings.period() == null || !Set.of("MONTH", "YEAR").contains(settings.period())) throw new BusinessException("排放评价周期无效");
        if (settings.parameters() != null && !settings.parameters().isEmpty()) throw new BusinessException("内置排放模板不接受自定义数值参数");
        if ("YEAR_END_FORECAST".equals(settings.template()) && !"YEAR".equals(settings.period())) {
            throw new BusinessException("年末预测必须按年度评价");
        }
        boolean completeness = "COMPLETENESS".equals(settings.template());
        if (settings.threshold() == null || settings.recoveryThreshold() == null
                || settings.threshold().signum() < 0 || settings.recoveryThreshold().signum() < 0
                || completeness && (settings.threshold().compareTo(BigDecimal.valueOf(100)) > 0
                || settings.recoveryThreshold().compareTo(BigDecimal.valueOf(100)) > 0)
                || completeness && settings.recoveryThreshold().compareTo(settings.threshold()) < 0
                || !completeness && settings.recoveryThreshold().compareTo(settings.threshold()) > 0) {
            throw new BusinessException("排放阈值或恢复阈值无效");
        }
        boolean comparison = Set.of("MONTH_ON_MONTH", "YEAR_ON_YEAR", "BASELINE").contains(settings.template());
        if (comparison && (settings.changeMode() == null || !Set.of("ABS", "PERCENT").contains(settings.changeMode()))) {
            throw new BusinessException("排放变化方式须为ABS或PERCENT");
        }
        if (!comparison && settings.changeMode() != null && !settings.changeMode().isBlank()
                && !Set.of("ABS", "PERCENT").contains(settings.changeMode())) {
            throw new BusinessException("排放变化方式须为ABS或PERCENT");
        }
        if ("BASELINE".equals(settings.template()) && blank(settings.baselineModelId())) {
            throw new BusinessException("基线规则必须指定已生效模型");
        }
        if (!"BASELINE".equals(settings.template()) && settings.baselineModelId() != null
                && !settings.baselineModelId().isBlank()) {
            throw new BusinessException("仅基线规则支持模型标识");
        }
        if ("YEAR_END_FORECAST".equals(settings.template()) && !"LINEAR_DAY".equals(settings.forecastMethod())) {
            throw new BusinessException("年末预测仅支持已确认的LINEAR_DAY方法");
        }
        if (!"YEAR_END_FORECAST".equals(settings.template()) && settings.forecastMethod() != null
                && !settings.forecastMethod().isBlank()) {
            throw new BusinessException("仅年末预测规则支持预测方法");
        }
    }

    public DeviceResult evaluate(String building, List<Rule> rules, long now) {
        List<Rule> applicable = rules == null ? List.of() : rules.stream()
                .filter(rule -> rule != null && rule.enabled() && "EMISSION".equals(rule.type())).toList();
        if (applicable.isEmpty()) return new DeviceResult(List.of(), true, null, Set.of());

        List<Candidate> candidates = new ArrayList<>();
        Set<String> unknownRules = new java.util.LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        for (Rule rule : applicable) {
            EmissionSettings settings = rule.advanced() == null ? null : rule.advanced().emission();
            String type = candidateType(settings == null ? null : settings.template());
            String reason = invalidSettings(settings);
            ResolvedPeriod period = resolvePeriod(settings, now);
            if (reason == null && period == null) reason = "当前评价周期尚无已结束月份";

            Metric metric = null;
            if (reason == null) {
                try {
                    List<AlarmEmissionSource> matchedSources = sources.stream().filter(candidate -> candidate.supports(settings)).toList();
                    if (matchedSources.isEmpty()) reason = "排放数据来源或已确认专业口径暂不可用";
                    else if (matchedSources.size() > 1) reason = "多个排放数据来源匹配，无法确定唯一来源";
                    else {
                        metric = matchedSources.getFirst().load(building, rule, period, now);
                        if (metric == null) reason = "排放数据来源未返回评价结果";
                        else if (!metric.available()) reason = nonBlank(metric.unavailableReason(), "排放评价数据暂不可用");
                        else reason = validateMetric(settings, metric, building, period, now);
                    }
                } catch (RuntimeException unavailable) {
                    reason = "排放数据来源读取失败";
                }
            }

            if (reason != null) {
                unknownRules.add(rule.id());
                reasons.add(rule.name() + "：" + reason);
                if (type != null) candidates.add(candidate(rule, type, settings, period, null, false, null, reason, now));
                continue;
            }

            boolean completeness = "COMPLETENESS".equals(settings.template());
            boolean confirmedMissing = completeness && !blank(metric.confirmedMissingFactorsEvidence());
            BigDecimal evaluated = evaluateValue(settings, metric, period);
            if (evaluated == null && !confirmedMissing) {
                reason = "比较基准为零或比较数据缺失，无法评价";
                unknownRules.add(rule.id());
                reasons.add(rule.name() + "：" + reason);
                if (type != null) candidates.add(candidate(rule, type, settings, period, metric, false, null, reason, now));
                continue;
            }
            boolean matched = completeness
                    ? confirmedMissing || evaluated.compareTo(settings.threshold()) < 0
                    : evaluated.compareTo(settings.threshold()) > 0;
            boolean recovered = completeness
                    ? !confirmedMissing && evaluated.compareTo(settings.recoveryThreshold()) >= 0
                    : evaluated.compareTo(settings.recoveryThreshold()) <= 0;
            candidates.add(candidate(rule, type, settings, period, metric, matched, recovered,
                    completeness ? matched ? "完整率低于阈值或存在已确认缺因子" : "完整率已达到恢复阈值"
                            : matched ? "超过排放规则阈值" : "排放指标未超过规则阈值", now, evaluated));
        }
        boolean complete = unknownRules.isEmpty();
        return new DeviceResult(candidates, true, complete ? null : String.join("；", reasons), Set.copyOf(unknownRules));
    }

    private String invalidSettings(EmissionSettings settings) {
        if (settings == null) return "排放规则配置无效";
        RuleRequest request = new RuleRequest("EMISSION", "检查", "INFO", "Q0", null, null,
                true, 1, 1L, null, null, null, "ALL", "EMISSION", List.of(),
                new Advanced(null, null, null, null, null, settings));
        try { templates.validate(request); return null; }
        catch (BusinessException invalid) { return invalid.getMessage(); }
    }

    /** 评价期按北京时间闭月确定，也供主服务在来源缺数时关闭已过期的旧期间事件。 */
    public static ResolvedPeriod resolvePeriod(EmissionSettings settings, long now) {
        if (settings == null) return null;
        YearMonth current = YearMonth.from(Instant.ofEpochMilli(now).atZone(BUSINESS_ZONE));
        YearMonth closed = current.minusMonths(1);
        if ("MONTH".equals(settings.period())) return new ResolvedPeriod(closed.toString(), closed, closed);
        if ("YEAR".equals(settings.period())) {
            YearMonth first = YearMonth.of(current.getYear(), 1);
            return closed.isBefore(first) ? null : new ResolvedPeriod(first.getYear() + "-01/" + closed, first, closed);
        }
        return null;
    }

    private String validateMetric(EmissionSettings settings, Metric metric, String building, ResolvedPeriod period, long now) {
        if (!building.equals(metric.buildingId())) return "排放指标建筑归属与请求不一致";
        if (period == null || !period.period().equals(metric.period())) return "排放指标评价期间与请求不一致";
        if (metric.observedAt() <= 0 || metric.observedAt() > now) return "排放指标观测时间缺失或晚于当前时间";
        if (blank(metric.evidence())) return "缺少正式来源证据";
        if(AlarmTemplateCatalog.isBuiltIn(settings.template())&&!("COMPLETENESS".equals(settings.template())&&metric.value()==null&&!blank(metric.confirmedMissingFactorsEvidence()))) {
            String expectedUnit=switch(settings.template()) { case "INTENSITY"->"kgCO2e/m²"; case "COMPLETENESS"->"%"; default->"tCO2e"; };
            if(!expectedUnit.equals(metric.unit())) return "正式指标单位与模板不一致，须由来源完成单位转换";
        }
        return switch (settings.template()) {
            case "QUOTA_PROGRESS" -> metric.value() == null || metric.value().signum() < 0 || metric.reference() == null
                    || metric.reference().signum() <= 0 || blank(metric.batchId()) || blank(metric.planVersion())
                    ? "缺少正式排放批次或评价期计划配额" : "YEAR".equals(settings.period())
                    && (blank(metric.quotaProgressEvidence()) || blank(metric.annualBudgetProgressEvidence()))
                    ? "年度配额进度与年度预算进度证据未分别提供" : null;
            case "INTENSITY" -> metric.value() == null || metric.value().signum() < 0 || blank(metric.unit())
                    || blank(metric.batchId()) || blank(metric.modelVersion()) ? "缺少正式强度批次、单位或面积版本" : null;
            case "MONTH_ON_MONTH", "YEAR_ON_YEAR" -> metric.value() == null || metric.reference() == null
                    || blank(metric.batchId()) ? "缺少正式对照批次或对照值" : null;
            case "BASELINE" -> metric.value() == null || metric.reference() == null || blank(metric.batchId())
                    || blank(metric.modelVersion()) ? "缺少正式批次或已生效基线模型版本" : null;
            case "COMPLETENESS" -> blank(metric.batchId())
                    || metric.value() == null && blank(metric.confirmedMissingFactorsEvidence())
                    || metric.value() != null && (metric.value().signum() < 0
                    || metric.value().compareTo(BigDecimal.valueOf(100)) > 0 || !"%".equals(metric.unit()))
                    ? "缺少正式完整率批次、有效百分比或已确认缺失事实" : null;
            case "YEAR_END_FORECAST" -> metric.reference() == null || metric.reference().signum() <= 0
                    || blank(metric.batchId()) || blank(metric.planVersion()) || blank(metric.modelVersion())
                    || metric.forecastInput() == null || !metric.forecastInput().methodConfirmed()
                    || blank(metric.forecastInput().modelEvidence()) ? "缺少已确认LINEAR_DAY年累计、模型或年度配额依据" : null;
            default -> {
                AlarmTemplateProvider provider = templates.provider(settings.template());
                boolean versioned = !blank(metric.batchId()) || !blank(metric.planVersion()) || !blank(metric.modelVersion());
                yield provider == null || !provider.descriptor().unit().equals(metric.unit()) || metric.value() == null || !versioned
                        ? "自定义模板来源、单位、值或版本依据无效" : null;
            }
        };
    }

    private BigDecimal evaluateValue(EmissionSettings settings, Metric metric, ResolvedPeriod period) {
        if ("YEAR_END_FORECAST".equals(settings.template())) {
            BigDecimal yearToDate = metric.forecastInput().yearToDateValue();
            if (yearToDate == null || yearToDate.signum() < 0) return null;
            int elapsedDays = period.toMonth().atEndOfMonth().getDayOfYear();
            int yearDays = period.toMonth().isLeapYear() ? 366 : 365;
            if (elapsedDays <= 0 || metric.reference() == null || metric.reference().signum() <= 0) return null;
            BigDecimal predicted = yearToDate.multiply(BigDecimal.valueOf(yearDays)).divide(BigDecimal.valueOf(elapsedDays), 8, RoundingMode.HALF_UP);
            return predicted.multiply(BigDecimal.valueOf(100)).divide(metric.reference(), 8, RoundingMode.HALF_UP);
        }
        if ("QUOTA_PROGRESS".equals(settings.template())) {
            if (metric.value() == null || metric.reference() == null || metric.reference().signum() <= 0) return null;
            return metric.value().multiply(BigDecimal.valueOf(100)).divide(metric.reference(), 8, RoundingMode.HALF_UP);
        }
        if (!Set.of("MONTH_ON_MONTH", "YEAR_ON_YEAR", "BASELINE").contains(settings.template())) return metric.value();
        if (metric.value() == null || metric.reference() == null) return null;
        BigDecimal difference = metric.value().subtract(metric.reference());
        if ("ABS".equals(settings.changeMode())) return difference;
        if (metric.reference().signum() <= 0) return null;
        return difference.multiply(BigDecimal.valueOf(100)).divide(metric.reference(), 8, RoundingMode.HALF_UP);
    }

    private Candidate candidate(Rule rule, String type, EmissionSettings settings, ResolvedPeriod period, Metric metric,
            boolean matched, Boolean recoveryMatched, String description, long now) {
        return candidate(rule, type, settings, period, metric, matched, recoveryMatched, description, now, null);
    }

    private Candidate candidate(Rule rule, String type, EmissionSettings settings, ResolvedPeriod period, Metric metric,
            boolean matched, Boolean recoveryMatched, String description, long now, BigDecimal evaluated) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (settings != null) {
            evidence.put("template", settings.template());
            evidence.put("period", period == null ? settings.period() : period.period());
            evidence.put("threshold", settings.threshold());
            evidence.put("recoveryThreshold", settings.recoveryThreshold());
            if (settings.changeMode() != null) evidence.put("changeMode", settings.changeMode());
            if (settings.baselineModelId() != null) evidence.put("baselineModelId", settings.baselineModelId());
            if (settings.forecastMethod() != null) evidence.put("forecastMethod", settings.forecastMethod());
        }
        if (period != null) {
            evidence.put("fromMonth", period.fromMonth().toString());
            evidence.put("toMonth", period.toMonth().toString());
        }
        if (metric != null) {
            evidence.put("sourceEvidence", metric.evidence());
            evidence.put("value", metric.value());
            evidence.put("reference", metric.reference());
            evidence.put("unit", metric.unit());
            evidence.put("evaluatedValue", evaluated);
            evidence.put("evaluatedUnit", evaluatedUnit(settings, metric));
            evidence.put("batchId", metric.batchId());
            evidence.put("planVersion", metric.planVersion());
            evidence.put("modelVersion", metric.modelVersion());
            // 配额评价值与年度预算进度是不同事实；来源证据原样保留，不用其中之一替代另一个。
            evidence.put("quotaProgressEvidence", "QUOTA_PROGRESS".equals(settings.template()) ? metric.quotaProgressEvidence() : null);
            evidence.put("annualBudgetProgressEvidence", "QUOTA_PROGRESS".equals(settings.template()) ? metric.annualBudgetProgressEvidence() : null);
            if (metric.forecastInput() != null) {
                evidence.put("forecastYearToDate", metric.forecastInput().yearToDateValue());
                evidence.put("forecastElapsedDays", period == null ? null : period.toMonth().atEndOfMonth().getDayOfYear());
                evidence.put("forecastYearDays", period == null ? null : period.toMonth().isLeapYear() ? 366 : 365);
                BigDecimal predicted = period == null || metric.forecastInput().yearToDateValue() == null ? null
                        : metric.forecastInput().yearToDateValue().multiply(BigDecimal.valueOf(period.toMonth().isLeapYear() ? 366 : 365))
                        .divide(BigDecimal.valueOf(period.toMonth().atEndOfMonth().getDayOfYear()), 8, RoundingMode.HALF_UP);
                evidence.put("forecastTCO2e", predicted);
                evidence.put("annualQuotaTCO2e", metric.reference());
                evidence.put("forecastMethodConfirmed", metric.forecastInput().methodConfirmed());
                evidence.put("forecastModelEvidence", metric.forecastInput().modelEvidence());
            }
            if (metric.confirmedMissingFactorsEvidence() != null) evidence.put("confirmedMissingFactorsEvidence", metric.confirmedMissingFactorsEvidence());
        }
        if (description != null && (metric == null || !metric.available())) evidence.put("unavailableReason", description);
        long at = metric == null || metric.observedAt() <= 0 ? now : metric.observedAt();
        String objectName = metric == null ? buildingName(rule) : nonBlank(metric.objectName(), buildingName(rule));
        try {
            return new Candidate(type, rule.id(), "BUILDING", objectName, null, null, null,
                    period == null ? null : period.period(), severity(type, evaluated), description, json.writeValueAsString(evidence),
                    matched, at, metric == null ? null : metric.observationKey(),
                    metric == null ? null : metric.observationCycleKey(), recoveryMatched);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法编码排放报警证据", failure);
        }
    }

    private String candidateType(String template) {
        if (template == null) return null;
        return switch (template) {
            case "QUOTA_PROGRESS" -> "QUOTA";
            case "INTENSITY" -> "INTENSITY";
            case "MONTH_ON_MONTH" -> "MOM";
            case "YEAR_ON_YEAR" -> "YOY";
            case "BASELINE" -> "BASELINE";
            case "COMPLETENESS" -> "COMPLETENESS";
            case "YEAR_END_FORECAST" -> "FORECAST";
            default -> {
                AlarmTemplateProvider provider = templates.provider(template);
                yield provider == null ? null : provider.descriptor().eventType();
            }
        };
    }

    private static String severity(String type, BigDecimal evaluated) {
        return "QUOTA".equals(type) && evaluated != null && evaluated.compareTo(BigDecimal.valueOf(100)) > 0
                ? "IMPORTANT" : "WARNING";
    }

    private static String evaluatedUnit(EmissionSettings settings, Metric metric) {
        if (Set.of("QUOTA_PROGRESS", "YEAR_END_FORECAST", "COMPLETENESS").contains(settings.template())
                || "PERCENT".equals(settings.changeMode())) return "%";
        return metric == null ? null : metric.unit();
    }

    private static String buildingName(Rule rule) { return "建筑排放"; }
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
