package com.platform.iot.formula.model;

import java.util.List;

public record FormulaCalculationException(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        FormulaCalculation.Status status,
        String reasonCode,
        List<String> missingInputs,
        String formulaVersion,
        long calculatedAt) {

    public FormulaCalculationException {
        missingInputs = List.copyOf(missingInputs);
    }
}
