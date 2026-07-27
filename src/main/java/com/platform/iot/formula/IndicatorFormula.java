package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;

public interface IndicatorFormula {

    String indicatorCode();

    String formulaVersion();

    FormulaCalculation calculate(FormulaInputs inputs);
}
