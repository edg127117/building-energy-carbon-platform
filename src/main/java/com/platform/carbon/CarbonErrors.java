package com.platform.carbon;

import com.platform.framework.exception.BusinessException;

/** 碳规则、计算和自动重算共享的稳定错误码。 */
public final class CarbonErrors {
    public static final String UNAUTHORIZED = "CARBON_UNAUTHORIZED";
    public static final String VALIDATION_FAILED = "CARBON_VALIDATION_FAILED";
    public static final String FORBIDDEN = "CARBON_FORBIDDEN";
    public static final String NOT_FOUND = "CARBON_NOT_FOUND";
    public static final String VERSION_CONFLICT = "CARBON_VERSION_CONFLICT";
    public static final String STATUS_CONFLICT = "CARBON_STATUS_CONFLICT";
    public static final String FACTOR_MISSING = "CARBON_FACTOR_MISSING";
    public static final String FACTOR_CONFLICT = "CARBON_FACTOR_CONFLICT";
    public static final String UNIT_INCOMPATIBLE = "CARBON_UNIT_INCOMPATIBLE";
    public static final String ACTIVITY_INCOMPLETE = "CARBON_ACTIVITY_INCOMPLETE";
    public static final String IDEMPOTENCY_CONFLICT = "CARBON_IDEMPOTENCY_CONFLICT";
    public static final String CONCURRENT_CALCULATION = "CARBON_CONCURRENT_CALCULATION";
    public static final String LIMIT_EXCEEDED = "CARBON_LIMIT_EXCEEDED";
    public static final String CALCULATION_TIMEOUT = "CARBON_CALCULATION_TIMEOUT";
    public static final String RECALCULATION_CONFLICT = "CARBON_RECALCULATION_CONFLICT";
    public static final String APPROVAL_CONFLICT = "CARBON_APPROVAL_CONFLICT";
    public static final String DEPENDENCY_UNAVAILABLE = "CARBON_DEPENDENCY_UNAVAILABLE";

    private CarbonErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
