package com.platform.iot.onboarding;

import com.platform.framework.exception.BusinessException;

/** 设备接入 API 的稳定错误码和异常工厂。 */
public final class OnboardingErrors {
    public static final String UNAUTHORIZED = "ONBOARDING_UNAUTHORIZED";
    public static final String FORBIDDEN = "ONBOARDING_FORBIDDEN";
    public static final String NOT_FOUND = "ONBOARDING_NOT_FOUND";
    public static final String STATE_CONFLICT = "ONBOARDING_STATE_CONFLICT";
    public static final String VALIDATION_FAILED = "ONBOARDING_VALIDATION_FAILED";
    public static final String DUPLICATE = "ONBOARDING_DUPLICATE";
    public static final String CONFIG_PENDING = "ONBOARDING_CONFIG_PENDING";

    private OnboardingErrors() {
    }

    public static BusinessException error(int status, String errorCode, String message) {
        return new BusinessException(status, errorCode, message);
    }
}
