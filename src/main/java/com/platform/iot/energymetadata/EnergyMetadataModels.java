package com.platform.iot.energymetadata;

/** 能源测点专业属性的稳定领域枚举。 */
public final class EnergyMetadataModels {
    private EnergyMetadataModels() {
    }

    public enum EnergyType { ELECTRICITY, NATURAL_GAS, HEAT, COLD, FUEL }
    public enum EnergySubtype {
        GRID_PURCHASED, TRADED_PURCHASED, DIRECT_RENEWABLE, SELF_GENERATED
    }
    public enum ValueSemantics { INSTANTANEOUS, CUMULATIVE, PERIOD_TOTAL }
    public enum ReportingPeriod { MONTH }
    public enum ConfirmationStatus { PENDING_EXPERT, CONFIRMED }
}
