package com.platform.iot.formula.model;

import java.util.List;

public record FormulaCalculation(
        Status status,
        String indicatorCode,
        String formulaVersion,
        Double value,
        Integer dataQuality,
        List<Input> inputs,
        List<Step> steps,
        String reasonCode,
        List<String> missingInputs) {

    public enum Status { SUCCESS, MISSING_INPUT, INVALID_INPUT, ENGINE_ERROR }

    public record Input(
            String key, String pointId, String pointCode,
            double value, String unit, int dataQuality) {}

    public record Step(
            String code, String expression, double value, String unit) {}

    public FormulaCalculation {
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
        missingInputs = List.copyOf(missingInputs);
    }
}
