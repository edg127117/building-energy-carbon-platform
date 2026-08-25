package com.platform.iot.formula;

import com.platform.cache.IndicatorLatestCacheService;
import com.platform.config.FormulaProperties;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.formula.model.FormulaCalculationAttempt;
import com.platform.iot.formula.model.IndicatorMinuteState;
import com.platform.iot.formula.model.FormulaResultRevision;
import com.platform.iot.deviceparameter.DeviceParameterModels.ResolvedParameter;
import com.platform.iot.deviceparameter.DeviceParameterModels.ResolvedParameters;
import com.platform.iot.deviceparameter.formula.DeviceParameterFormulaImpactPort;
import com.platform.iot.deviceparameter.query.DeviceParameterResolver;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageErrors;
import com.platform.iot.qualityusage.QualityUsageSnapshotUnavailableException;
import com.platform.iot.qualityusage.QualityUsageRecoveryTaskService;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * HVAC 质量完成分钟到指标结果的核心编排器。
 *
 * <p>它位于分钟质量 READY 事件与 TDengine 指标仓储之间：选择活动指标、组装输入、
 * 调用纯公式、持久化成功或失败审计，然后以最佳努力更新 Redis 和 WebSocket。
 * 正常冻结事件直接使用事件快照，避免重复查询；恢复事件必须回查完整分钟，
 * 防止只用部分补写测点计算出错误结果。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "formula", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class HvacFormulaEngine implements DeviceParameterFormulaImpactPort {

    private static final Logger log = LoggerFactory.getLogger(HvacFormulaEngine.class);
    private static final String ENGINE_ERROR_REASON = "FORMULA_ENGINE_ERROR";
    private static final String MISSING_STRATEGY_REASON = "FORMULA_STRATEGY_MISSING";
    private static final String UNKNOWN_FORMULA_VERSION = "UNKNOWN";

    private final IndicatorConfigProvider configProvider;
    private final HvacMinuteRepository minuteRepository;
    private final IndicatorMinuteRepository indicatorRepository;
    private final IndicatorLatestCacheService cache;
    private final IndicatorRealtimePublisher publisher;
    private final FormulaInputAssembler assembler;
    private final FormulaDependencyResolver dependencyResolver;
    private final Map<String, IndicatorFormula> formulas;
    private final QualityUsagePolicyResolver qualityUsageResolver;
    private QualityUsageRecoveryTaskService recoveryTasks;
    private DeviceParameterResolver deviceParameterResolver;

    @Autowired
    public HvacFormulaEngine(
            IndicatorConfigProvider configProvider,
            HvacMinuteRepository minuteRepository,
            IndicatorMinuteRepository indicatorRepository,
            IndicatorLatestCacheService cache,
            IndicatorRealtimePublisher publisher,
            DataPointConfigProvider pointConfigProvider,
            FormulaProperties properties,
            QualityUsagePolicyResolver qualityUsageResolver) {
        this(configProvider, minuteRepository, indicatorRepository, cache, publisher,
                new FormulaInputAssembler(),
                new FormulaDependencyResolver(pointConfigProvider),
                List.of(
                        new ChillerCopFormula(),
                        new CoolingTowerEfficiencyFormula(
                                new PsychrometricWetBulbCalculator(), properties),
                        new PumpEfficiencyFormula(),
                        new AhuPowerEfficiencyFormula()),
                qualityUsageResolver);
    }

    HvacFormulaEngine(
            IndicatorConfigProvider configProvider,
            HvacMinuteRepository minuteRepository,
            IndicatorMinuteRepository indicatorRepository,
            IndicatorLatestCacheService cache,
            IndicatorRealtimePublisher publisher,
            FormulaInputAssembler assembler,
            FormulaDependencyResolver dependencyResolver,
            Collection<IndicatorFormula> formulas,
            QualityUsagePolicyResolver qualityUsageResolver) {
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
        this.minuteRepository = Objects.requireNonNull(minuteRepository, "minuteRepository");
        this.indicatorRepository = Objects.requireNonNull(
                indicatorRepository, "indicatorRepository");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.dependencyResolver = Objects.requireNonNull(
                dependencyResolver, "dependencyResolver");
        this.formulas = indexFormulas(formulas);
        this.qualityUsageResolver = Objects.requireNonNull(
                qualityUsageResolver, "qualityUsageResolver");
    }

    @Autowired(required = false)
    void setRecoveryTasks(QualityUsageRecoveryTaskService recoveryTasks) {
        this.recoveryTasks = recoveryTasks;
    }

    @Autowired(required = false)
    void setDeviceParameterResolver(DeviceParameterResolver deviceParameterResolver) {
        this.deviceParameterResolver = deviceParameterResolver;
    }

    /**
     * 消费分钟质量选择已经完成的 READY 事件。
     *
     * <p>首次冻结直接使用事件快照；恢复或历史修正携带受影响点位，必须从
     * TDengine 重新读取该建筑完整分钟，避免用局部修正输入计算错误结果。</p>
     */
    @EventListener
    public void onMinuteQualityReady(HvacMinuteQualityReadyEvent event) {
        Collection<BizIndicator> activeIndicators = configProvider.findAllActive();
        boolean authoritativeCorrection =
                authoritativeCorrection(event.source());
        Set<String> onlyIndicatorIds = null;
        if (authoritativeCorrection
                && !event.affectedPointIds().isEmpty()) {
            onlyIndicatorIds = dependencyResolver.resolve(
                    activeIndicators, event.affectedPointIds(), formulas.values());
            if (onlyIndicatorIds.isEmpty()) {
                return;
            }
        }
        List<RawMinuteAggregate> inputs = !event.affectedPointIds().isEmpty()
                ? minuteRepository.findByMinute(event.minuteStart(), event.buildingIds())
                : event.aggregates();
        calculateAndPersist(
                event.minuteStart(), event.finalizedAt(), inputs,
                event.buildingIds(), onlyIndicatorIds, activeIndicators,
                authoritativeCorrection);
    }

    void calculateAndPersist(
            long minuteStart,
            long calculatedAt,
            List<RawMinuteAggregate> aggregates,
            Set<String> onlyIndicatorIds) {
        Set<String> affectedBuildings = aggregates.stream()
                .map(RawMinuteAggregate::buildingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        calculateAndPersist(
                minuteStart, calculatedAt, aggregates,
                affectedBuildings, onlyIndicatorIds,
                configProvider.findAllActive(), false);
    }

    /** 策略刷新恢复只重算调用方已经按依赖关系筛出的指标。 */
    public void recalculateForQualityPolicy(
            long minuteStart,
            List<RawMinuteAggregate> aggregates,
            Set<String> indicatorIds) {
        Set<String> buildings = aggregates.stream()
                .map(RawMinuteAggregate::buildingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        calculateAndPersist(
                minuteStart, System.currentTimeMillis(), aggregates,
                buildings, indicatorIds, configProvider.findAllActive(), true);
    }

    /**
     * 使用指标成功行中持久化的精确公式版本重建计算过程。
     *
     * <p>历史结果绝不能静默切换到当前的其他公式版本；不支持的代码或版本由
     * 查询 API 以 409 明确告知调用方。</p>
     */
    public FormulaCalculation explain(
            BizIndicator indicator,
            long minuteStart,
            List<RawMinuteAggregate> aggregates,
            String formulaVersion) {
        Objects.requireNonNull(indicator, "indicator");
        Objects.requireNonNull(aggregates, "aggregates");
        IndicatorFormula formula = formulas.get(indicator.getIndicatorCode());
        if (formula == null
                || formulaVersion == null
                || formulaVersion.isBlank()
                || !Objects.equals(formula.formulaVersion(), formulaVersion)) {
            throw new BusinessException(409, "公式版本不受当前服务支持");
        }
        FormulaInputs inputs = assembler.assemble(
                indicator, minuteStart, aggregates);
        FormulaCalculation calculation = Objects.requireNonNull(
                formula.calculate(inputs), "formula calculation");
        validateCalculation(indicator, formula, formulaVersion, calculation);
        return calculation;
    }

    /**
     * 复用公式引擎的依赖口径定位受影响指标。
     *
     * <p>迟到 Q0 补偿用该结果检查 calculatedAt 水位，避免因为同建筑无关指标
     * 缺失而重复发布修正 READY。</p>
     */
    public Set<String> resolveAffectedIndicatorIds(
            Collection<BizIndicator> activeIndicators,
            Set<String> affectedPointIds) {
        return dependencyResolver.resolve(
                activeIndicators, affectedPointIds, formulas.values());
    }

    /**
     * 为受影响建筑筛选指标，逐个计算后按“TDengine → Redis → WebSocket”顺序提交结果。
     *
     * <p>单个公式缺失、输入失败或策略异常只生成该指标的异常审计，不阻断同分钟其他
     * 指标。迟到、插值或人工修正属于权威修正：成功和失败都保留为不可变尝试事实，
     * 当前状态投影负责使旧成功不再参与查询。</p>
     *
     * <p>本轮所有 TDengine 写入均成功后才更新最新缓存；缓存接受该分钟后才广播，
     * 从而保证查询真相先于实时状态，并阻止历史补算把页面最新状态回拨到旧分钟。</p>
     */
    private void calculateAndPersist(
            long minuteStart,
            long calculatedAt,
            List<RawMinuteAggregate> aggregates,
            Set<String> affectedBuildings,
            Set<String> onlyIndicatorIds,
            Collection<BizIndicator> activeIndicators,
            boolean allowSuccessInvalidation) {
        List<BizIndicator> indicators = activeIndicators.stream()
                .filter(indicator -> affectedBuildings.contains(indicator.getBuildingId()))
                .filter(indicator -> onlyIndicatorIds == null
                        || onlyIndicatorIds.contains(indicator.getIndicatorId()))
                .toList();

        List<CalculatedSuccess> successes = new ArrayList<>();
        List<CalculatedFailure> failures = new ArrayList<>();
        for (BizIndicator indicator : indicators) {
            String formulaVersion = UNKNOWN_FORMULA_VERSION;
            String parameterEvidence = "[]";
            try {
                IndicatorFormula formula = formulas.get(indicator.getIndicatorCode());
                if (formula == null) {
                    FormulaCalculation calculation = failureCalculation(
                            indicator.getIndicatorCode(), formulaVersion,
                            MISSING_STRATEGY_REASON);
                    failures.add(failure(
                            indicator, minuteStart, calculatedAt, calculation,
                            List.of(), 0, parameterEvidence));
                    continue;
                }
                formulaVersion = Objects.requireNonNull(
                        formula.formulaVersion(), "formula.formulaVersion");
                FormulaInputs inputs = assembler.assemble(indicator, minuteStart, aggregates);
                ResolvedParameters parameters = resolveParameters(
                        indicator, formula, minuteStart, calculatedAt);
                parameterEvidence = parameterEvidence(parameters);
                FormulaCalculation calculation = Objects.requireNonNull(
                        formula.requiredParameterCodes() == null
                                || formula.requiredParameterCodes().isEmpty()
                                ? formula.calculate(inputs)
                                : formula.calculate(inputs, parameters),
                        "formula calculation");
                validateCalculation(indicator, formula, formulaVersion, calculation);
                if (calculation.status() == FormulaCalculation.Status.SUCCESS) {
                    try {
                        ResolutionContext policyContext = qualityUsageResolver.runtimeContext();
                        List<Resolution> decisions = calculation.inputs().stream()
                                .map(input -> qualityUsageResolver.resolve(
                                        policyContext,
                                        input.pointId(),
                                        com.platform.iot.qualityusage.QualityUsageModels
                                                .INDICATOR_CALCULATION,
                                        minuteStart,
                                        input.dataQuality()))
                                .toList();
                        if (decisions.stream().anyMatch(
                                decision -> decision.decision() == Decision.BLOCK)) {
                            calculation = qualityBlockedCalculation(calculation);
                            failures.add(failure(
                                    indicator, minuteStart, calculatedAt, calculation,
                                    decisions, policyContext.configRevision(), parameterEvidence));
                        } else {
                            successes.add(success(
                                    indicator, minuteStart, calculatedAt, calculation,
                                    decisions, policyContext.configRevision(), parameterEvidence));
                        }
                    } catch (QualityUsageSnapshotUnavailableException exception) {
                        calculation = policyUnavailableCalculation(calculation);
                        failures.add(failure(
                                indicator, minuteStart, calculatedAt, calculation,
                                List.of(), 0, parameterEvidence));
                    } catch (BusinessException exception) {
                        if (!QualityUsageErrors.SCENARIO_DISABLED.equals(
                                exception.getErrorCode())) {
                            throw exception;
                        }
                        calculation = policyUnavailableCalculation(calculation);
                        failures.add(failure(
                                indicator, minuteStart, calculatedAt, calculation,
                                List.of(), 0, parameterEvidence));
                    }
                } else {
                    failures.add(failure(
                            indicator, minuteStart, calculatedAt, calculation,
                            List.of(), 0, parameterEvidence));
                }
            } catch (RuntimeException exception) {
                log.warn("HVAC formula attempt failed: indicatorId={}, minuteStart={}",
                        indicator.getIndicatorId(), minuteStart, exception);
                FormulaCalculation calculation = failureCalculation(
                        indicator.getIndicatorCode(), formulaVersion,
                        ENGINE_ERROR_REASON);
                failures.add(failure(
                        indicator, minuteStart, calculatedAt, calculation,
                        List.of(), 0, parameterEvidence));
            }
        }

        // TDengine 是指标真相来源；事实和当前状态投影都成功后才允许刷新缓存。
        if (!successes.isEmpty()) {
            indicatorRepository.saveResultRevisions(
                    successes.stream().map(CalculatedSuccess::revision).toList());
            indicatorRepository.saveSuccesses(
                    successes.stream().map(CalculatedSuccess::row).toList());
        }
        if (!failures.isEmpty()) {
            indicatorRepository.saveExceptions(
                    failures.stream().map(CalculatedFailure::row).toList());
        }
        List<FormulaCalculationAttempt> attempts = new ArrayList<>();
        successes.forEach(success -> attempts.add(success.attempt()));
        failures.forEach(failure -> attempts.add(failure.attempt()));
        indicatorRepository.saveAttempts(attempts);
        List<IndicatorMinuteState> states = new ArrayList<>();
        successes.forEach(success -> states.add(success.projection()));
        failures.forEach(failure -> states.add(failure.projection()));
        try {
            indicatorRepository.saveStates(states);
        } catch (RuntimeException exception) {
            if (recoveryTasks != null) {
                recoveryTasks.recordProjectionFailure(states, exception);
            }
            log.warn("Indicator facts persisted but state projection failed; recovery was recorded",
                    exception);
            return;
        }
        successes.forEach(success ->
                notifyLatest(success.state(), allowSuccessInvalidation));
        failures.forEach(failure ->
                notifyLatest(failure.state(), allowSuccessInvalidation));
    }

    /**
     * 校验公式策略返回值与当前指标实例的代码、版本和成功/失败契约一致。
     *
     * <p>策略返回畸形结果时将由外层转换为可审计的引擎错误；不能把非有限值、非法
     * 质量等级或空失败原因写入 TDengine，也不能让一个策略冒充其他指标或版本。</p>
     */
    private void validateCalculation(
            BizIndicator indicator,
            IndicatorFormula formula,
            String expectedFormulaVersion,
            FormulaCalculation calculation) {
        FormulaCalculation.Status status = Objects.requireNonNull(
                calculation.status(), "formula calculation.status");
        String calculationCode = requireText(
                calculation.indicatorCode(), "formula calculation.indicatorCode");
        String indicatorCode = requireText(
                indicator.getIndicatorCode(), "indicator.indicatorCode");
        String strategyCode = requireText(
                formula.indicatorCode(), "formula.indicatorCode");
        if (!calculationCode.equals(indicatorCode)
                || !calculationCode.equals(strategyCode)) {
            throw new IllegalArgumentException("Formula calculation indicatorCode mismatch");
        }

        String calculationVersion = requireText(
                calculation.formulaVersion(), "formula calculation.formulaVersion");
        if (!calculationVersion.equals(expectedFormulaVersion)) {
            throw new IllegalArgumentException("Formula calculation version mismatch");
        }

        if (status == FormulaCalculation.Status.SUCCESS) {
            Double value = Objects.requireNonNull(
                    calculation.value(), "successful formula value");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Successful formula value must be finite");
            }
            Integer quality = Objects.requireNonNull(
                    calculation.dataQuality(), "successful formula quality");
            if (quality < 0 || quality > 2) {
                throw new IllegalArgumentException(
                        "Successful formula quality must be between 0 and 2");
            }
        } else {
            requireText(
                    calculation.reasonCode(), "failed formula calculation.reasonCode");
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * 仅在 Redis 接受该状态为当前最新事实后广播 WebSocket 消息。
     * 权威修正允许同分钟成功状态被失败状态覆盖，普通冻结则不允许等分钟失效。
     */
    private void notifyLatest(
            IndicatorLatestState state,
            boolean allowEqualMinuteSuccessInvalidation) {
        // 缓存拒绝旧分钟后也不推送，避免前端被补算结果回拨到更早状态。
        if (cache.setIfNotOlder(state, allowEqualMinuteSuccessInvalidation)) {
            publisher.publish(state);
        }
    }

    /** 判断事件是否可以推翻同一指标分钟已经存在的成功结果。 */
    private boolean authoritativeCorrection(QualityEventSource source) {
        return source == QualityEventSource.INTERPOLATION_CORRECTION
                || source == QualityEventSource.LATE_REAL_CORRECTION
                || source == QualityEventSource.MANUAL_RECALCULATION;
    }

    private CalculatedFailure failure(
            BizIndicator indicator,
            long minuteStart,
            long calculatedAt,
            FormulaCalculation calculation,
            List<Resolution> decisions,
            long configRevision,
            String parameterEvidence) {
        String attemptId = id();
        return new CalculatedFailure(
                exceptionRow(indicator, minuteStart, calculatedAt, calculation),
                latestState(indicator, minuteStart, calculation),
                attempt(indicator, minuteStart, calculatedAt, calculation,
                        attemptId, decisions, configRevision, parameterEvidence),
                projection(indicator, minuteStart, calculatedAt,
                        calculation.status().name(), null, attemptId, configRevision));
    }

    private CalculatedSuccess success(
            BizIndicator indicator,
            long minuteStart,
            long calculatedAt,
            FormulaCalculation calculation,
            List<Resolution> decisions,
            long configRevision,
            String parameterEvidence) {
        String attemptId = id();
        String revisionId = id();
        IndicatorMinuteResult row = successRow(
                indicator, minuteStart, calculatedAt, calculation);
        return new CalculatedSuccess(
                row,
                new FormulaResultRevision(revisionId, attemptId,
                        indicator.getIndicatorId(), indicator.getIndicatorCode(),
                        indicator.getBuildingId(), indicator.getSystemGroupId(),
                        indicator.getEquipId(), minuteStart, row.value(), row.dataQuality(),
                        row.formulaVersion(), parameterEvidence, calculatedAt),
                latestState(indicator, minuteStart, calculation),
                attempt(indicator, minuteStart, calculatedAt, calculation,
                        attemptId, decisions, configRevision, parameterEvidence),
                projection(indicator, minuteStart, calculatedAt,
                        "SUCCESS", revisionId,
                        attemptId, configRevision));
    }

    private FormulaCalculationAttempt attempt(
            BizIndicator indicator,
            long minuteStart,
            long calculatedAt,
            FormulaCalculation calculation,
            String attemptId,
            List<Resolution> decisions,
            long configRevision,
            String parameterEvidence) {
        return new FormulaCalculationAttempt(
                attemptId,
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getSystemGroupId(),
                indicator.getEquipId(),
                minuteStart,
                calculatedAt,
                calculation.status().name(),
                calculation.reasonCode(),
                com.platform.iot.qualityusage.QualityUsageModels.INDICATOR_CALCULATION,
                calculation.formulaVersion(),
                policyEvidence(calculation.inputs(), decisions),
                parameterEvidence,
                configRevision);
    }

    private IndicatorMinuteState projection(
            BizIndicator indicator,
            long minuteStart,
            long calculatedAt,
            String status,
            String sourceFactId,
            String attemptId,
            long configRevision) {
        return new IndicatorMinuteState(
                indicator.getIndicatorId(), indicator.getIndicatorCode(),
                indicator.getBuildingId(), indicator.getSystemGroupId(),
                indicator.getEquipId(), minuteStart, status, sourceFactId,
                attemptId, calculatedAt, configRevision);
    }

    private IndicatorMinuteResult successRow(
            BizIndicator indicator,
            long minuteStart,
            long calculatedAt,
            FormulaCalculation calculation) {
        return new IndicatorMinuteResult(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getSystemGroupId(),
                indicator.getEquipId(),
                minuteStart,
                Objects.requireNonNull(calculation.value(), "successful formula value"),
                Objects.requireNonNull(calculation.dataQuality(), "successful formula quality"),
                calculation.formulaVersion(),
                calculatedAt);
    }

    private FormulaCalculationException exceptionRow(
            BizIndicator indicator,
            long minuteStart,
            long calculatedAt,
            FormulaCalculation calculation) {
        return new FormulaCalculationException(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getSystemGroupId(),
                indicator.getEquipId(),
                minuteStart,
                calculation.status(),
                calculation.reasonCode(),
                calculation.missingInputs(),
                calculation.formulaVersion(),
                calculatedAt);
    }

    private IndicatorLatestState latestState(
            BizIndicator indicator,
            long minuteStart,
            FormulaCalculation calculation) {
        return new IndicatorLatestState(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getEquipId(),
                minuteStart,
                calculation.status(),
                calculation.value(),
                calculation.dataQuality(),
                calculation.formulaVersion(),
                calculation.reasonCode(),
                calculation.missingInputs(),
                calculation.inputs(),
                calculation.steps());
    }

    private FormulaCalculation failureCalculation(
            String indicatorCode, String formulaVersion, String reasonCode) {
        return new FormulaCalculation(
                FormulaCalculation.Status.ENGINE_ERROR,
                indicatorCode,
                formulaVersion,
                null,
                null,
                List.of(),
                List.of(),
                reasonCode,
                List.of());
    }

    private FormulaCalculation qualityBlockedCalculation(FormulaCalculation successful) {
        return new FormulaCalculation(
                FormulaCalculation.Status.QUALITY_NOT_ALLOWED,
                successful.indicatorCode(), successful.formulaVersion(),
                null, null, successful.inputs(), List.of(),
                "QUALITY_NOT_ALLOWED", List.of());
    }

    private FormulaCalculation policyUnavailableCalculation(FormulaCalculation successful) {
        return new FormulaCalculation(
                FormulaCalculation.Status.POLICY_SNAPSHOT_UNAVAILABLE,
                successful.indicatorCode(), successful.formulaVersion(),
                null, null, successful.inputs(), List.of(),
                "POLICY_SNAPSHOT_UNAVAILABLE", List.of());
    }

    private String policyEvidence(
            List<FormulaCalculation.Input> inputs,
            List<Resolution> decisions) {
        if (decisions.isEmpty()) {
            return "[]";
        }
        List<String> blocked = new ArrayList<>();
        for (int index = 0; index < decisions.size(); index++) {
            Resolution decision = decisions.get(index);
            if (decision.decision() != Decision.BLOCK) {
                continue;
            }
            blocked.add("{\"pointId\":\"" + inputs.get(index).pointId()
                        + "\",\"quality\":" + decision.actualQuality()
                        + ",\"decision\":\"" + decision.decision().name()
                        + "\",\"policyVersion\":"
                        + (decision.policyVersion() == null
                                ? "null" : decision.policyVersion())
                        + ",\"configRevision\":" + decision.configRevision() + "}");
        }
        return blocked.stream().sorted().collect(Collectors.joining(",", "[", "]"));
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, IndicatorFormula> indexFormulas(
            Collection<IndicatorFormula> formulaStrategies) {
        Objects.requireNonNull(formulaStrategies, "formulaStrategies");
        Map<String, IndicatorFormula> indexed = new LinkedHashMap<>();
        for (IndicatorFormula formula : formulaStrategies) {
            Objects.requireNonNull(formula, "formula");
            String indicatorCode = requireText(
                    formula.indicatorCode(), "formula.indicatorCode");
            IndicatorFormula duplicate = indexed.putIfAbsent(
                    indicatorCode, formula);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "Duplicate formula strategy: " + indicatorCode);
            }
        }
        return Map.copyOf(indexed);
    }

    private record CalculatedSuccess(
            IndicatorMinuteResult row,
            FormulaResultRevision revision,
            IndicatorLatestState state,
            FormulaCalculationAttempt attempt,
            IndicatorMinuteState projection) {}

    private record CalculatedFailure(
            FormulaCalculationException row,
            IndicatorLatestState state,
            FormulaCalculationAttempt attempt,
            IndicatorMinuteState projection) {}

    private ResolvedParameters resolveParameters(
            BizIndicator indicator,
            IndicatorFormula formula,
            long minuteStart,
            long calculatedAt) {
        Set<String> codes = formula.requiredParameterCodes();
        LocalDateTime businessTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(minuteStart), ZoneId.of("Asia/Shanghai"));
        LocalDateTime knowledgeTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(calculatedAt), ZoneId.of("Asia/Shanghai"));
        if (codes == null || codes.isEmpty()) {
            return new ResolvedParameters(indicator.getEquipId(), businessTime, knowledgeTime,
                    null, null, Map.of());
        }
        if (deviceParameterResolver == null || indicator.getEquipId() == null) {
            throw new IllegalStateException("Formula device parameter resolver unavailable");
        }
        return deviceParameterResolver.resolve(
                indicator.getEquipId(), businessTime, knowledgeTime, codes);
    }

    private String parameterEvidence(ResolvedParameters parameters) {
        if (parameters.values().isEmpty()) {
            return "[]";
        }
        return parameters.values().values().stream()
                .sorted(Comparator.comparing(ResolvedParameter::parameterCode))
                .map(value -> "{\"parameterCode\":\"" + escape(value.parameterCode())
                        + "\",\"definitionId\":\"" + escape(value.definitionId())
                        + "\",\"versionId\":\"" + escape(value.versionId())
                        + "\",\"timelineRevisionId\":\"" + escape(value.timelineRevisionId())
                        + "\",\"standardUnit\":\"" + escape(value.standardUnit())
                        + "\",\"sourceType\":\"" + escape(value.sourceType().name())
                        + "\",\"sourceReference\":\"" + escape(value.sourceReference())
                        + "\",\"businessEffectiveFrom\":\""
                        + value.businessEffectiveFrom() + "\",\"publishedAt\":\""
                        + value.publishedAt() + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public List<String> affectedIndicatorIds(
            String equipmentId,
            Collection<String> changedParameterCodes,
            LocalDateTime from,
            LocalDateTime to) {
        Set<String> changed = changedParameterCodes == null
                ? Set.of() : Set.copyOf(changedParameterCodes);
        if (changed.isEmpty()) {
            return List.of();
        }
        return configProvider.findAllActive().stream()
                .filter(indicator -> Objects.equals(equipmentId, indicator.getEquipId()))
                .filter(indicator -> {
                    IndicatorFormula formula = formulas.get(indicator.getIndicatorCode());
                    return formula != null && formula.requiredParameterCodes().stream()
                            .anyMatch(changed::contains);
                })
                .map(BizIndicator::getIndicatorId).distinct().sorted().toList();
    }

    @Override
    public void markPending(
            String timelineRevisionId,
            String equipmentId,
            Collection<String> indicatorIds,
            LocalDateTime from,
            LocalDateTime to) {
        markParameterRecalculationState(timelineRevisionId, equipmentId, indicatorIds,
                from, to, "PENDING_RECALC");
    }

    @Override
    public void markFailed(
            String timelineRevisionId,
            String equipmentId,
            Collection<String> indicatorIds,
            LocalDateTime from,
            LocalDateTime to) {
        markParameterRecalculationState(timelineRevisionId, equipmentId, indicatorIds,
                from, to, "RECALC_FAILED");
    }

    @Override
    public void recalculateMinute(
            String buildingId, long minuteStart, Collection<String> indicatorIds) {
        List<RawMinuteAggregate> inputs = minuteRepository.findByMinute(
                minuteStart, Set.of(buildingId));
        calculateAndPersist(minuteStart, System.currentTimeMillis(), inputs,
                Set.of(buildingId), Set.copyOf(indicatorIds),
                configProvider.findAllActive(), true);
    }

    private void markParameterRecalculationState(
            String timelineRevisionId,
            String equipmentId,
            Collection<String> indicatorIds,
            LocalDateTime from,
            LocalDateTime to,
            String status) {
        long fromMillis = from.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long toMillis = to.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        List<IndicatorMinuteResult> results = indicatorIds.stream()
                .flatMap(indicatorId -> indicatorRepository.findHistory(
                        indicatorId, fromMillis, toMillis).stream())
                .filter(result -> Objects.equals(equipmentId, result.equipId()))
                .toList();
        Set<com.platform.iot.formula.model.IndicatorMinuteKey> keys = results.stream()
                .map(result -> new com.platform.iot.formula.model.IndicatorMinuteKey(
                        result.indicatorId(), result.minuteStart()))
                .collect(Collectors.toSet());
        Map<com.platform.iot.formula.model.IndicatorMinuteKey, IndicatorMinuteState> current =
                indicatorRepository.findStates(keys);
        long updatedAt = System.currentTimeMillis();
        indicatorRepository.saveStates(results.stream().map(result -> {
            IndicatorMinuteState previous = current.get(
                    new com.platform.iot.formula.model.IndicatorMinuteKey(
                            result.indicatorId(), result.minuteStart()));
            return new IndicatorMinuteState(result.indicatorId(), result.indicatorCode(),
                    result.buildingId(), result.systemGroupId(), result.equipId(),
                    result.minuteStart(), status,
                    previous == null ? result.indicatorId() + ':' + result.minuteStart()
                            : previous.sourceFactId(),
                    timelineRevisionId, updatedAt,
                    previous == null ? 0 : previous.configRevision());
        }).toList());
    }
}
