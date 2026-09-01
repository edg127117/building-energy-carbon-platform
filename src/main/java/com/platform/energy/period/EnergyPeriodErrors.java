package com.platform.energy.period;

import com.platform.framework.exception.BusinessException;

/** 周期投影、封账和重算 API 使用的稳定错误码。 */
public final class EnergyPeriodErrors {
    public static final String UNAUTHORIZED = "ENERGY_PERIOD_UNAUTHORIZED";
    public static final String FORBIDDEN = "ENERGY_PERIOD_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_PERIOD_VALIDATION_FAILED";
    public static final String NOT_FOUND = "ENERGY_PERIOD_NOT_FOUND";
    public static final String POLICY_REQUIRED = "ENERGY_PERIOD_POLICY_REQUIRED";
    public static final String LOCK_BLOCKED = "ENERGY_PERIOD_LOCK_BLOCKED";
    public static final String STATUS_CONFLICT = "ENERGY_PERIOD_STATUS_CONFLICT";
    public static final String VERSION_CONFLICT = "ENERGY_PERIOD_VERSION_CONFLICT";
    public static final String IDEMPOTENCY_CONFLICT = "ENERGY_PERIOD_IDEMPOTENCY_CONFLICT";
    public static final String HISTORICAL_RULE_CONFLICT = "ENERGY_PERIOD_HISTORICAL_RULE_CONFLICT";
    public static final String VALUE_STORE_UNAVAILABLE = "ENERGY_PERIOD_VALUE_STORE_UNAVAILABLE";

    private EnergyPeriodErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
