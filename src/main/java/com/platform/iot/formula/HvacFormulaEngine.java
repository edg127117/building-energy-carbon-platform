package com.platform.iot.formula;

import com.platform.cache.IndicatorLatestCacheService;
import com.platform.config.FormulaProperties;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.aggregation.HvacMinuteBatchFrozenEvent;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Map<String, IndicatorFormula> formulas;

    public HvacFormulaEngine(
            IndicatorConfigProvider configProvider,
            HvacMinuteRepository minuteRepository,
            IndicatorMinuteRepository indicatorRepository,
            IndicatorLatestCacheService cache,
            IndicatorRealtimePublisher publisher,
            FormulaProperties properties) {
        this(configProvider, minuteRepository, indicatorRepository, cache, publisher,
                new FormulaInputAssembler(), List.of(
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
            Collection<IndicatorFormula> formulas) {
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
        this.minuteRepository = Objects.requireNonNull(minuteRepository, "minuteRepository");
        this.indicatorRepository = Objects.requireNonNull(
                indicatorRepository, "indicatorRepository");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.formulas = indexFormulas(formulas);
    }

    @EventListener
    public void onMinuteFrozen(HvacMinuteBatchFrozenEvent event) {
        Set<String> buildingIds = event.aggregates().stream()
                .map(RawMinuteAggregate::buildingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<RawMinuteAggregate> inputs = event.recovery()
                ? minuteRepository.findByMinute(event.minuteStart(), buildingIds)
                : event.aggregates();
        calculateAndPersist(event.minuteStart(), event.finalizedAt(), inputs);
    }

    private void calculateAndPersist(
            long minuteStart,
            long calculatedAt,
            List<RawMinuteAggregate> aggregates) {
        Set<String> affectedBuildings = aggregates.stream()
                .map(RawMinuteAggregate::buildingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<BizIndicator> indicators = configProvider.findAllActive().stream()
                .filter(indicator -> affectedBuildings.contains(indicator.getBuildingId()))
                .toList();

        List<CalculatedSuccess> successes = new ArrayList<>();
        List<CalculatedFailure> failures = new ArrayList<>();
        for (BizIndicator indicator : indicators) {
            IndicatorFormula formula = formulas.get(indicator.getIndicatorCode());
            String formulaVersion = UNKNOWN_FORMULA_VERSION;
            try {
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

        if (!successes.isEmpty()) {
            indicatorRepository.saveSuccesses(
                    successes.stream().map(CalculatedSuccess::row).toList());
            successes.forEach(success -> notifyLatest(success.state()));
        }
        if (!failures.isEmpty()) {
            indicatorRepository.saveExceptions(
                    failures.stream().map(CalculatedFailure::row).toList());
            failures.forEach(failure -> notifyLatest(failure.state()));
        }
    }

    private void notifyLatest(IndicatorLatestState state) {
        if (cache.setIfNotOlder(state)) {
            publisher.publish(state);
        }
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
            IndicatorFormula duplicate = indexed.putIfAbsent(
                    Objects.requireNonNull(formula.indicatorCode(), "formula.indicatorCode"),
                    formula);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "Duplicate formula strategy: " + formula.indicatorCode());
            }
        }
        return Map.copyOf(indexed);
    }

    private record CalculatedSuccess(
            IndicatorMinuteResult row, IndicatorLatestState state) {}

    private record CalculatedFailure(
            FormulaCalculationException row, IndicatorLatestState state) {}
}
