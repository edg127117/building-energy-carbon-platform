package com.platform.iot.formula.model;

import java.util.List;

public record IndicatorLatestState(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String equipId,
        long minuteStart,
        FormulaCalculation.Status status,
        Double value,
        Integer dataQuality,
        String formulaVersion,
        String reasonCode,
        List<String> missingInputs,
        List<FormulaCalculation.Input> inputs,
        List<FormulaCalculation.Step> steps) {

    public IndicatorLatestState {
        missingInputs = List.copyOf(missingInputs);
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
    }
}
