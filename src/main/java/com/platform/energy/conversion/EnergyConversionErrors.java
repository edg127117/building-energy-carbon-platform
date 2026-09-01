package com.platform.energy.conversion;

import com.platform.framework.exception.BusinessException;

/** 折标规则治理和确定性计算对外使用的稳定错误码。 */
public final class EnergyConversionErrors {
    public static final String UNAUTHORIZED = "ENERGY_CONVERSION_UNAUTHORIZED";
    public static final String FORBIDDEN = "ENERGY_CONVERSION_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_CONVERSION_VALIDATION_FAILED";
    public static final String NOT_FOUND = "ENERGY_CONVERSION_NOT_FOUND";
    public static final String VERSION_CONFLICT = "ENERGY_CONVERSION_VERSION_CONFLICT";
    public static final String STATUS_CONFLICT = "ENERGY_CONVERSION_STATUS_CONFLICT";
    public static final String RULE_MISSING = "ENERGY_CONVERSION_RULE_MISSING";
    public static final String RULE_CONFLICT = "ENERGY_CONVERSION_RULE_CONFLICT";
    public static final String UNIT_INCOMPATIBLE = "ENERGY_CONVERSION_UNIT_INCOMPATIBLE";
    public static final String REFERENCE_VERSION_CONFLICT = "ENERGY_CONVERSION_REFERENCE_VERSION_CONFLICT";
    public static final String MOBILE_SCOPE_REJECTED = "ENERGY_CONVERSION_MOBILE_SCOPE_REJECTED";

    private EnergyConversionErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
