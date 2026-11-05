package com.platform.alarm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import com.platform.alarm.AlarmEmissionModels.Metric;
import com.platform.alarm.AlarmEmissionModels.ResolvedPeriod;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.platform.alarm.AlarmModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlarmEmissionExtensionTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final long novemberShanghai = Instant.parse("2026-11-04T16:30:00Z").toEpochMilli();

    @Test
    void resolvesOnlyClosedMonthAndYearThroughLatestClosedMonth() {
        ResolvedPeriod month = AlarmEmissionEvaluator.resolvePeriod(settings("QUOTA_PROGRESS", "MONTH", null, null, null), novemberShanghai);
        ResolvedPeriod year = AlarmEmissionEvaluator.resolvePeriod(settings("INTENSITY", "YEAR", null, null, null), novemberShanghai);

        assertThat(month.period()).isEqualTo("2026-10");
        assertThat(year.period()).isEqualTo("2026-01/2026-10");
        assertThat(AlarmEmissionEvaluator.resolvePeriod(settings("INTENSITY", "YEAR", null, null, null),
                Instant.parse("2026-01-02T00:00:00Z").toEpochMilli())).isNull();
    }

    @Test
    void evaluatesPercentChangeWithIndependentRecoveryThresholdAndZeroReferenceIsUnknown() {
        AlarmEmissionEvaluator evaluator = evaluator((building, rule, period, now) ->
                metric(building, period, now, new BigDecimal("120"), new BigDecimal("100"), "tCO2e", "batch-1", null, null));
        Rule rule = rule("r1", settings("MONTH_ON_MONTH", "MONTH", "PERCENT", null, null),
                new BigDecimal("15"), new BigDecimal("5"));

        Candidate candidate = evaluator.evaluate("B1", List.of(rule), novemberShanghai).candidates().getFirst();

        assertThat(candidate.type()).isEqualTo("MOM");
        assertThat(candidate.matched()).isTrue();
        assertThat(candidate.recoveryMatched()).isFalse();
        assertThat(candidate.period()).isEqualTo("2026-10");
        assertThat(candidate.objectName()).isEqualTo("A楼");

        AlarmEmissionEvaluator zero = evaluator((building, r, period, now) ->
                metric(building, period, now, BigDecimal.ONE, BigDecimal.ZERO, "tCO2e", "batch-1", null, null));
        var result = zero.evaluate("B1", List.of(rule("r2", settings("YEAR_ON_YEAR", "YEAR", "PERCENT", null, null),
                BigDecimal.ONE, BigDecimal.ZERO)), novemberShanghai);
        assertThat(result.complete()).isTrue();
        assertThat(result.unavailableRuleIds()).contains("r2");
        assertThat(result.candidates().getFirst().recoveryMatched()).isNull();
    }

    @Test
    void absoluteComparisonPreservesDirectionAndQuotaEvidenceKeepsSeparateFacts() throws Exception {
        AlarmEmissionEvaluator comparison = evaluator((building, rule, period, now) ->
                metric(building, period, now, new BigDecimal("90"), new BigDecimal("100"), "tCO2e", "batch-1", null, null));
        Candidate absolute = comparison.evaluate("B1", List.of(rule("r1",
                settings("YEAR_ON_YEAR", "YEAR", "ABS", null, null), new BigDecimal("9"), BigDecimal.ZERO)), novemberShanghai)
                .candidates().getFirst();
        assertThat(absolute.matched()).isFalse();

        AlarmEmissionEvaluator growth = evaluator((building, rule, period, now) ->
                metric(building, period, now, new BigDecimal("110"), new BigDecimal("100"), "tCO2e", "batch-1", null, null));
        assertThat(growth.evaluate("B1", List.of(rule("r-growth",
                settings("YEAR_ON_YEAR", "YEAR", "ABS", null, null), new BigDecimal("9"), BigDecimal.ZERO)), novemberShanghai)
                .candidates().getFirst().matched()).isTrue();

        AlarmEmissionEvaluator quota = evaluator((building, rule, period, now) -> new Metric(true, null,
                new BigDecimal("810"), new BigDecimal("1000"), "tCO2e", "{\"source\":\"approved\"}", now, null, null, "A楼",
                "quota evidence 81", "budget progress 83", null, building, period.period(), "batch-q", "plan-v2", null, null));
        Candidate candidate = quota.evaluate("B1", List.of(rule("q1",
                settings("QUOTA_PROGRESS", "YEAR", null, null, null), new BigDecimal("80"), new BigDecimal("75"))), novemberShanghai)
                .candidates().getFirst();
        JsonNode evidence = json.readTree(candidate.evidence());
        assertThat(candidate.type()).isEqualTo("QUOTA");
        assertThat(candidate.matched()).isTrue();
        assertThat(candidate.severity()).isEqualTo("WARNING");
        assertThat(evidence.path("evaluatedValue").decimalValue()).isEqualByComparingTo("81");
        assertThat(evidence.path("unit").asText()).isEqualTo("tCO2e");
        assertThat(evidence.path("evaluatedUnit").asText()).isEqualTo("%");
        assertThat(evidence.path("quotaProgressEvidence").asText()).isEqualTo("quota evidence 81");
        assertThat(evidence.path("annualBudgetProgressEvidence").asText()).isEqualTo("budget progress 83");

        AlarmEmissionEvaluator overQuota = evaluator((building, rule, period, now) -> new Metric(true, null,
                new BigDecimal("1010"), new BigDecimal("1000"), "tCO2e", "approved current batch", now, null, null,
                "A楼", "quota actual 1010", "annual budget progress 83", null,
                building, period.period(), "batch-over", "plan-v2", null, null));
        Candidate over = overQuota.evaluate("B1", List.of(rule("q2",
                settings("QUOTA_PROGRESS", "YEAR", null, null, null), new BigDecimal("80"), new BigDecimal("75"))), novemberShanghai)
                .candidates().getFirst();
        assertThat(over.matched()).isTrue();
        assertThat(over.severity()).isEqualTo("IMPORTANT");
    }

    @Test
    void missingSourceAndUnconfirmedMetricAreUnknownWithoutBlockingOtherRules() {
        AlarmEmissionEvaluator noSource = new AlarmEmissionEvaluator(List.of(), json);
        Rule first = rule("missing", settings("INTENSITY", "MONTH", null, null, null), BigDecimal.ONE, BigDecimal.ZERO);
        Rule second = rule("good", settings("COMPLETENESS", "MONTH", null, null, null), new BigDecimal("60"), new BigDecimal("70"));
        AlarmEmissionEvaluator mixed = evaluator((building, rule, period, now) -> rule.id().equals("missing")
                ? new Metric(false, "专业口径尚未确认", null, null, null, null, now, null, null, null)
                : metric(building, period, now, new BigDecimal("50"), null, "%", "batch-complete", null, null));

        var absent = noSource.evaluate("B1", List.of(first), novemberShanghai);
        var isolated = mixed.evaluate("B1", List.of(first, second), novemberShanghai);

        assertThat(absent.complete()).isTrue();
        assertThat(absent.unavailableRuleIds()).containsExactly("missing");
        assertThat(isolated.candidates()).hasSize(2);
        assertThat(isolated.candidates().get(0).matched()).isFalse();
        assertThat(isolated.candidates().get(1).matched()).isTrue();
        assertThat(isolated.unavailableRuleIds()).containsExactly("missing");
        assertThat(isolated.complete()).isTrue();
    }

    @Test
    void rejectsForeignPeriodFutureOrAmbiguousSourceFactsPerRule() {
        Rule rule = rule("r-scope", settings("INTENSITY", "MONTH", null, null, null), BigDecimal.ONE, BigDecimal.ZERO);
        AlarmEmissionEvaluator foreign = evaluator((building, current, period, now) ->
                metric("B2", period, now, BigDecimal.ONE, null, "kgCO2e/m²", "batch", null, "area-v1"));
        AlarmEmissionEvaluator wrongPeriod = evaluator((building, current, period, now) -> new Metric(true, null,
                BigDecimal.ONE, null, "kgCO2e/m²", "formal source evidence", now, "obs", "cycle", "A楼",
                null, null, null, building, "2026-09", "batch", null, "area-v1", null));
        AlarmEmissionEvaluator future = evaluator((building, current, period, now) ->
                metric(building, period, now + 1, BigDecimal.ONE, null, "kgCO2e/m²", "batch", null, "area-v1"));
        AlarmEmissionEvaluator wrongUnit = evaluator((building,current,period,now)->
                metric(building,period,now,BigDecimal.ONE,null,"tCO2e/person","batch",null,"area-v1"));
        AlarmEmissionSource source = source((building, current, period, now) ->
                metric(building, period, now, BigDecimal.ONE, null, "kgCO2e/m²", "batch", null, "area-v1"));
        AlarmEmissionEvaluator ambiguous = new AlarmEmissionEvaluator(List.of(source, source), json);

        assertThat(foreign.evaluate("B1", List.of(rule), novemberShanghai).unavailableRuleIds()).contains("r-scope");
        assertThat(wrongPeriod.evaluate("B1", List.of(rule), novemberShanghai).unavailableRuleIds()).contains("r-scope");
        assertThat(future.evaluate("B1", List.of(rule), novemberShanghai).unavailableRuleIds()).contains("r-scope");
        assertThat(wrongUnit.evaluate("B1",List.of(rule),novemberShanghai).unavailableRuleIds()).contains("r-scope");
        assertThat(ambiguous.evaluate("B1", List.of(rule), novemberShanghai).unavailableReason()).contains("多个排放数据来源");
    }

    @Test
    void confirmedMissingFactorTriggersCompletenessRegardlessOfAggregateRate() {
        AlarmEmissionEvaluator evaluator = evaluator((building, rule, period, now) -> new Metric(true, null,
                null, null, null, "formal source evidence", now, null, null, "A楼", null, null, null,
                building, period.period(), "batch-completeness", null, null, "确认缺少电梯用电因子"));
        Rule rule = rule("r-completeness", settings("COMPLETENESS", "MONTH", null, null, null),
                new BigDecimal("70"), new BigDecimal("95"));

        Candidate candidate = evaluator.evaluate("B1", List.of(rule), novemberShanghai).candidates().getFirst();

        assertThat(candidate.matched()).isTrue();
        assertThat(candidate.recoveryMatched()).isFalse();
        assertThat(candidate.evidence()).contains("确认缺少电梯用电因子");
    }

    @Test
    void registeredCustomTemplatePublishesMetadataAndUsesSharedThresholdEvaluation() {
        AlarmTemplateProvider provider = new AlarmTemplateProvider() {
            @Override public AlarmTemplateCatalog.Descriptor descriptor() {
                return new AlarmTemplateCatalog.Descriptor("CUSTOM_INDEX", "自定义已核验指标", List.of("MONTH"), "%",
                        List.of("period", "threshold", "recoveryThreshold", "parameters.actual", "parameters.factor"),
                        List.of("待专业确认：具体口径由提供者声明"), "INTENSITY",
                        java.util.Map.of("actual", "正式值", "factor", "换算系数"));
            }
            @Override public Metric load(String building, Rule rule, ResolvedPeriod period, long now) {
                var parameters = rule.advanced().emission().parameters();
                BigDecimal calculated = parameters.get("actual").multiply(parameters.get("factor"));
                return metric(building, period, now, calculated, null, "%", "custom-batch", null, null);
            }
        };
        AlarmTemplateCatalog catalog = new AlarmTemplateCatalog(List.of(provider));
        EmissionSettings settings = new EmissionSettings("CUSTOM_INDEX", "MONTH", new BigDecimal("60"), new BigDecimal("55"),
                null, null, null, java.util.Map.of("actual", new BigDecimal("30"), "factor", new BigDecimal("2.1")));
        Rule rule = rule("custom", settings, settings.threshold(), settings.recoveryThreshold());

        catalog.validate(request(settings));
        Candidate candidate = new AlarmEmissionEvaluator(List.of(provider), json)
                .evaluate("B1", List.of(rule), novemberShanghai).candidates().getFirst();

        assertThat(catalog.list()).anySatisfy(descriptor -> {
            assertThat(descriptor.code()).isEqualTo("CUSTOM_INDEX");
            assertThat(descriptor.requiredFields()).contains("threshold");
            assertThat(descriptor.professionalInputs()).containsExactly("待专业确认：具体口径由提供者声明");
            assertThat(descriptor.parameterLabels()).containsEntry("actual", "正式值").containsEntry("factor", "换算系数");
        });
        assertThat(candidate.type()).isEqualTo("INTENSITY");
        assertThat(candidate.matched()).isTrue();
    }

    @Test
    void injectedSourcePersistsWarningRecoversAndFeedsAllEmissionSummaryGroups() throws Exception {
        AlarmChainTest fixture = new AlarmChainTest();
        fixture.setup();
        AtomicInteger observations = new AtomicInteger();
        AlarmEmissionSource source = new AlarmEmissionSource() {
            @Override public boolean supports(EmissionSettings settings) { return "QUOTA_PROGRESS".equals(settings.template()); }
            @Override public Metric load(String building, Rule rule, ResolvedPeriod period, long now) {
                int sequence = observations.getAndIncrement();
                BigDecimal actual = sequence == 0 ? new BigDecimal("600") : new BigDecimal("400");
                return new Metric(true, null, actual, new BigDecimal("1000"), "tCO2e", "formal accounting evidence",
                        now, "quota-observation-" + sequence, "QUOTA:" + sequence, "建筑A",
                        "quota actual batch " + sequence, "annual budget progress evidence", null,
                        building, period.period(), "batch-" + sequence, "plan-v1", null, null);
            }
        };
        AlarmEmissionEvaluator evaluator = new AlarmEmissionEvaluator(List.of(source), json);
        AlarmService target = AopTestUtils.getTargetObject(fixture.service);
        ReflectionTestUtils.setField(target, "emissionEvaluator", evaluator);
        EmissionSettings settings = settings("QUOTA_PROGRESS", "MONTH", null, null, null,
                new BigDecimal("50"), new BigDecimal("40"));
        String ruleId = fixture.service.createRule(1L, AlarmChainTest.ADMIN, "A", new RuleRequest("EMISSION", "建筑排放进度",
                null, "Q0", null, null, true, 1, 0L, 1, 0, 0, "ALL", null, List.of(),
                new Advanced(null, null, null, null, null, settings)));

        fixture.service.evaluate("A");
        var active = fixture.service.events(1L, AlarmChainTest.ADMIN, "A", false, "EMISSION", 1, 20).items();
        assertThat(active).singleElement().satisfies(event -> {
            assertThat(event.ruleId()).isEqualTo(ruleId);
            assertThat(event.severity()).isEqualTo("WARNING");
            assertThat(event.period()).isEqualTo("2026-10");
            assertThat(event.evidence()).contains("\"evaluatedUnit\":\"%\"", "\"batchId\":\"batch-0\"");
        });
        assertThat(fixture.service.summary(1L, AlarmChainTest.ADMIN, "A").emissionGroups())
                .hasSize(7).anySatisfy(group -> assertThat(group).isEqualTo(new Group("QUOTA", 1)));

        fixture.clock.advance(60_000);
        fixture.service.evaluate("A");
        assertThat(fixture.service.events(1L, AlarmChainTest.ADMIN, "A", false, "EMISSION", 1, 20).total()).isZero();
        var recovered = fixture.service.events(1L, AlarmChainTest.ADMIN, "A", true, "EMISSION", 1, 20).items().getFirst();
        assertThat(recovered.status()).isEqualTo("RECOVERED");
        assertThat(recovered.recoveredAt()).isNotNull();
        assertThat(recovered.period()).isEqualTo("2026-10");
        assertThat(fixture.service.summary(1L, AlarmChainTest.ADMIN, "A").emissionWarnings()).isZero();
    }

    @Test
    void baselineAndForecastRequireExplicitConfirmedMethods() throws Exception {
        assertThatThrownBy(() -> AlarmEmissionEvaluator.validate(request(settings("BASELINE", "YEAR", "ABS", null, null))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AlarmEmissionEvaluator.validate(request(settings("YEAR_END_FORECAST", "YEAR", null, null, "MODEL_FIT"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AlarmEmissionEvaluator.validate(request(settings("COMPLETENESS", "YEAR", null, null, null,
                new BigDecimal("2"), BigDecimal.ONE))))
                .isInstanceOf(BusinessException.class);

        AlarmEmissionEvaluator forecast = evaluator((building, rule, period, now) ->
                new Metric(false, "模型确认缺失", null, null, null, null, now, null, null, null));
        Rule line = rule("f1", settings("YEAR_END_FORECAST", "YEAR", null, null, "LINEAR_DAY"), BigDecimal.ONE, BigDecimal.ZERO);
        var unknown = forecast.evaluate("B1", List.of(line), novemberShanghai);
        assertThat(unknown.complete()).isTrue();
        assertThat(unknown.unavailableReason()).contains("模型确认缺失");

        AlarmEmissionEvaluator confirmed = evaluator((building, rule, period, now) -> new Metric(true, null,
                null, new BigDecimal("100"), "tCO2e", "annual aggregate", now, null, null, "A楼", null, null,
                new AlarmEmissionModels.ForecastInput(new BigDecimal("100"), true, "model snapshot v3"),
                building, "2026-01/2026-10", "batch-f", "plan-v4", "model-v3", null));
        Rule approved = rule("f2", settings("YEAR_END_FORECAST", "YEAR", null, null, "LINEAR_DAY"),
                new BigDecimal("120"), new BigDecimal("110"));
        var extrapolated = confirmed.evaluate("B1", List.of(approved), novemberShanghai);
        assertThat(extrapolated.candidates().getFirst().matched()).isTrue();
        assertThat(extrapolated.candidates().getFirst().severity()).isEqualTo("WARNING");
        assertThat(json.readTree(extrapolated.candidates().getFirst().evidence()).path("forecastElapsedDays").asInt()).isEqualTo(304);
        assertThat(json.readTree(extrapolated.candidates().getFirst().evidence()).path("evaluatedUnit").asText()).isEqualTo("%");
        assertThat(json.readTree(extrapolated.candidates().getFirst().evidence()).path("evaluatedValue").decimalValue())
                .isEqualByComparingTo("120.06578947");
    }

    private AlarmEmissionEvaluator evaluator(MetricLoader loader) {
        return new AlarmEmissionEvaluator(List.of(source(loader)), json);
    }

    private AlarmEmissionSource source(MetricLoader loader) {
        return new AlarmEmissionSource() {
            @Override public boolean supports(EmissionSettings settings) { return settings != null; }
            @Override public Metric load(String building, Rule rule, ResolvedPeriod period, long now) {
                return loader.load(building, rule, period, now);
            }
        };
    }

    private static Metric metric(String building, ResolvedPeriod period, long now, BigDecimal value, BigDecimal reference,
            String unit, String batch, String planVersion, String modelVersion) {
        return new Metric(true, null, value, reference, unit, "formal source evidence", now, "obs", "cycle", "A楼",
                null, null, null, building, period.period(), batch, planVersion, modelVersion, null);
    }

    private static Rule rule(String id, EmissionSettings settings, BigDecimal threshold, BigDecimal recovery) {
        return new Rule(id, "EMISSION", "规则" + id, null, "Q0", null, null, true, 1, 1L,
                null, null, 1, 0, 0, "ALL", "EMISSION", List.of(),
                new Advanced(null, null, null, null, null, new EmissionSettings(settings.template(), settings.period(), threshold,
                        recovery, settings.changeMode(), settings.baselineModelId(), settings.forecastMethod(), settings.parameters())));
    }

    private static EmissionSettings settings(String template, String period, String mode, String baseline, String forecast) {
        return settings(template, period, mode, baseline, forecast, BigDecimal.ONE, BigDecimal.ZERO);
    }

    private static EmissionSettings settings(String template, String period, String mode, String baseline, String forecast,
            BigDecimal threshold, BigDecimal recovery) {
        return new EmissionSettings(template, period, threshold, recovery, mode, baseline, forecast);
    }

    private static RuleRequest request(EmissionSettings settings) {
        return new RuleRequest("EMISSION", "测试规则", "IMPORTANT", "Q0", null, null, true, 1, 0L,
                1, 0, 0, "ALL", "EMISSION", List.of(), new Advanced(null, null, null, null, null, settings));
    }

    @FunctionalInterface
    private interface MetricLoader {
        Metric load(String building, Rule rule, ResolvedPeriod period, long now);
    }
}
