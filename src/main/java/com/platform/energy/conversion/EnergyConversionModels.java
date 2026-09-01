package com.platform.energy.conversion;

/** 折标参数、公式和计算证据使用的受控枚举。 */
public final class EnergyConversionModels {
    private EnergyConversionModels() {
    }

    public enum RuleStatus { PENDING_EXPERT, APPROVED, DISABLED }
    public enum ConversionMethod { DIRECT_TCE_FACTOR, LOWER_HEATING_VALUE, ENERGY_EQUIVALENT }
    public enum ConversionPerspective { CALORIFIC_EQUIVALENT, PRIMARY_EQUIVALENT }
    public enum FormulaAlgorithm {
        DIRECT_TCE_FACTOR_V1,
        LOWER_HEATING_VALUE_V1,
        ENERGY_EQUIVALENT_V1
    }
    public enum ParameterUnit {
        TCE_PER_INPUT_UNIT,
        GJ_PER_INPUT_UNIT,
        MJ_PER_INPUT_UNIT,
        GJ_PER_TCE
    }
    public enum RuleUsageScope { DEVELOPMENT_SIMULATION, PRODUCTION }
    public enum ResultNature { DEVELOPMENT_SIMULATION, FORMAL }
}
