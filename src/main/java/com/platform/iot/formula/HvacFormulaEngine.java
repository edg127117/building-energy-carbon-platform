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
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.quality.DataPointConfigProvider;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HVAC 质量完成分钟到指标结果的核心编排器。
 *
 * <p>它位于分钟聚合事件与 TDengine 指标仓储之间：选择活动指标、组装输入、
 * 调用纯公式、持久化成功或失败审计，然后以最佳努力更新 Redis 和 WebSocket。
 * 正常冻结事件直接使用事件快照，避免重复查询；恢复事件必须回查完整分钟，
 * 防止只用部分补写测点计算出错误结果。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "formula", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class HvacFormulaEngine {

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

    @Autowired
    public HvacFormulaEngine(
            IndicatorConfigProvider configProvider,
            HvacMinuteRepository minuteRepository,
            IndicatorMinuteRepository indicatorRepository,
            IndicatorLatestCacheService cache,
            IndicatorRealtimePublisher publisher,
            DataPointConfigProvider pointConfigProvider,
            FormulaProperties properties) {
        this(configProvider, minuteRepository, indicatorRepository, cache, publisher,
                new FormulaInputAssembler(),
                new FormulaDependencyResolver(pointConfigProvider),
                List.of(
                        new ChillerCopFormula(),
                        new CoolingTowerEfficiencyFormula(
                                new PsychrometricWetBulbCalculator(), properties),
                        new PumpEfficiencyFormula(),
                        new AhuPowerEfficiencyFormula()));
    }

    HvacFormulaEngine(
            IndicatorConfigProvider configProvider,
            HvacMinuteRepository minuteRepository,
            IndicatorMinuteRepository indicatorRepository,
            IndicatorLatestCacheService cache,
            IndicatorRealtimePublisher publisher,
            FormulaInputAssembler assembler,
            FormulaDependencyResolver dependencyResolver,
            Collection<IndicatorFormula> formulas) {
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
            try {
                IndicatorFormula formula = formulas.get(indicator.getIndicatorCode());
                if (formula == null) {
                    FormulaCalculation calculation = failureCalculation(
                            indicator.getIndicatorCode(), formulaVersion,
                            MISSING_STRATEGY_REASON);
                    failures.add(failure(indicator, minuteStart, calculatedAt, calculation));
                    continue;
                }
                formulaVersion = Objects.requireNonNull(
                        formula.formulaVersion(), "formula.formulaVersion");
                FormulaInputs inputs = assembler.assemble(indicator, minuteStart, aggregates);
                FormulaCalculation calculation = Objects.requireNonNull(
                        formula.calculate(inputs), "formula calculation");
                validateCalculation(indicator, formula, formulaVersion, calculation);
                if (calculation.status() == FormulaCalculation.Status.SUCCESS) {
                    successes.add(new CalculatedSuccess(
                            successRow(indicator, minuteStart, calculatedAt, calculation),
                            latestState(indicator, minuteStart, calculation)));
                } else {
                    failures.add(failure(indicator, minuteStart, calculatedAt, calculation));
                }
            } catch (RuntimeException exception) {
                log.warn("HVAC formula attempt failed: indicatorId={}, minuteStart={}",
                        indicator.getIndicatorId(), minuteStart, exception);
                FormulaCalculation calculation = failureCalculation(
                        indicator.getIndicatorCode(), formulaVersion,
                        ENGINE_ERROR_REASON);
                failures.add(failure(indicator, minuteStart, calculatedAt, calculation));
            }
        }

        // TDengine 是指标真相来源；本事件所有写入/删除全部成功后才允许刷新缓存。
        if (!successes.isEmpty()) {
            indicatorRepository.saveSuccesses(
                    successes.stream().map(CalculatedSuccess::row).toList());
        }
        if (allowSuccessInvalidation && !failures.isEmpty()) {
            indicatorRepository.deleteSuccesses(failures.stream()
                    .map(failure -> new IndicatorMinuteKey(
                            failure.row().indicatorId(),
                            failure.row().minuteStart()))
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        if (!failures.isEmpty()) {
            indicatorRepository.saveExceptions(
                    failures.stream().map(CalculatedFailure::row).toList());
        }
        successes.forEach(success ->
                notifyLatest(success.state(), allowSuccessInvalidation));
        failures.forEach(failure ->
                notifyLatest(failure.state(), allowSuccessInvalidation));
    }

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

    private void notifyLatest(
            IndicatorLatestState state,
            boolean allowEqualMinuteSuccessInvalidation) {
        // 缓存拒绝旧分钟后也不推送，避免前端被补算结果回拨到更早状态。
        if (cache.setIfNotOlder(state, allowEqualMinuteSuccessInvalidation)) {
            publisher.publish(state);
        }
    }

    private boolean authoritativeCorrection(QualityEventSource source) {
        return source == QualityEventSource.INTERPOLATION_CORRECTION
                || source == QualityEventSource.LATE_REAL_CORRECTION
                || source == QualityEventSource.MANUAL_RECALCULATION;
    }

    private CalculatedFailure failure(
            BizIndicator indicator,
            long minuteStart,
            long calculatedAt,
            FormulaCalculation calculation) {
        return new CalculatedFailure(
                exceptionRow(indicator, minuteStart, calculatedAt, calculation),
                latestState(indicator, minuteStart, calculation));
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
            IndicatorMinuteResult row, IndicatorLatestState state) {}

    private record CalculatedFailure(
            FormulaCalculationException row, IndicatorLatestState state) {}
}
