package com.platform.energy.activity;

import com.platform.framework.exception.BusinessException;

/** 多能源活动数据只读接口对外公开的稳定错误码。 */
public final class EnergyActivityDataErrors {
    public static final String FORBIDDEN = "ENERGY_ACTIVITY_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_ACTIVITY_VALIDATION_FAILED";
    public static final String POINT_PROFILE_REQUIRED = "ENERGY_ACTIVITY_POINT_PROFILE_REQUIRED";
    public static final String POINT_PROFILE_UNCONFIRMED = "ENERGY_ACTIVITY_POINT_PROFILE_UNCONFIRMED";
    public static final String DATA_SCOPE_MISMATCH = "ENERGY_ACTIVITY_DATA_SCOPE_MISMATCH";
    public static final String DEPENDENCY_UNAVAILABLE = "ENERGY_ACTIVITY_DEPENDENCY_UNAVAILABLE";

    private EnergyActivityDataErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
