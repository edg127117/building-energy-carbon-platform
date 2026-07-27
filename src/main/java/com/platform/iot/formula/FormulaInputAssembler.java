package com.platform.iot.formula;

import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.temporal.model.RawMinuteAggregate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds the calculation inputs for one indicator from a frozen minute.
 */
public final class FormulaInputAssembler {

    public FormulaInputs assemble(
            BizIndicator indicator,
            long requestedMinute,
            Collection<RawMinuteAggregate> aggregates) {
        Objects.requireNonNull(indicator, "indicator");
        Objects.requireNonNull(aggregates, "aggregates");
        String buildingId = Objects.requireNonNull(
                indicator.getBuildingId(), "indicator.buildingId");

        Map<String, List<FormulaCalculation.Input>> inputsByKey = new TreeMap<>();
        for (RawMinuteAggregate aggregate : aggregates) {
            Objects.requireNonNull(aggregate, "aggregate");
            if (!matchesCommonScope(aggregate, buildingId, requestedMinute)) {
                continue;
            }

            boolean environment = aggregate.equipId() == null;
            if (!environment && !Objects.equals(aggregate.equipId(), indicator.getEquipId())) {
                continue;
            }

            String key = semanticKey(aggregate, environment);
            FormulaCalculation.Input input = new FormulaCalculation.Input(
                    key,
                    aggregate.pointId(),
                    aggregate.pointCode(),
                    aggregate.averageValue(),
                    null,
                    aggregate.dataQuality());
            inputsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(input);
        }

        inputsByKey.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .findFirst()
                .ifPresent(entry -> {
                    throw new IllegalArgumentException(
                            "Duplicate formula input key: " + entry.getKey());
                });

        return new FormulaInputs(inputsByKey.values().stream()
                .map(inputs -> inputs.get(0))
                .toList());
    }

    private boolean matchesCommonScope(
            RawMinuteAggregate aggregate, String buildingId, long requestedMinute) {
        return aggregate.isForCalc() == 1
                && aggregate.minuteStart() == requestedMinute
                && buildingId.equals(aggregate.buildingId());
    }

    private String semanticKey(RawMinuteAggregate aggregate, boolean environment) {
        String prefix = environment ? aggregate.familyCode() : aggregate.componentCode();
        if (prefix == null || prefix.isBlank()
                || aggregate.suffixCode() == null || aggregate.suffixCode().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing formula input key metadata for point: " + aggregate.pointId());
        }
        return prefix + "/" + aggregate.suffixCode();
    }
}
