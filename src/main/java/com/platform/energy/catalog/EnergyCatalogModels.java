package com.platform.energy.catalog;

/** 能源品种、单位量纲和测点绑定共同使用的受控枚举。 */
public final class EnergyCatalogModels {
    private EnergyCatalogModels() {
    }

    public enum CatalogStatus { DRAFT, PENDING_EXPERT, APPROVED, DISABLED }
    public enum SourceType { STANDARD, EXCEL, MANUAL }
    public enum UsageScope {
        STATIONARY_COMBUSTION, MOBILE_COMBUSTION, PURCHASED_ELECTRICITY, PURCHASED_HEAT
    }
    public enum DimensionCode {
        POWER, ENERGY, ACTUAL_VOLUME, NORMAL_VOLUME, MASS, STANDARD_COAL_EQUIVALENT
    }
    public enum ConversionType { IDENTITY, FIXED_SCALE, REQUIRES_BUSINESS_RULE }
    public enum ConversionRequirement { NONE, TIME_INTEGRATION, STANDARD_CONDITION, BUSINESS_RULE }
    public enum BindingStatus { PENDING_EXPERT, CONFIRMED, DISABLED }
}
