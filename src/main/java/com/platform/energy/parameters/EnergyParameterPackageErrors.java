package com.platform.energy.parameters;

import com.platform.framework.exception.BusinessException;

/** 参数包入口的稳定错误码；具体依赖错误继续保留原治理服务的码。 */
public final class EnergyParameterPackageErrors {
    public static final String UNAUTHORIZED = "ENERGY_PARAMETER_PACKAGE_UNAUTHORIZED";
    public static final String FORBIDDEN = "ENERGY_PARAMETER_PACKAGE_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_PARAMETER_PACKAGE_VALIDATION_FAILED";
    public static final String PREVIEW_BLOCKED = "ENERGY_PARAMETER_PACKAGE_PREVIEW_BLOCKED";
    public static final String IDEMPOTENCY_CONFLICT = "ENERGY_PARAMETER_PACKAGE_IDEMPOTENCY_CONFLICT";
    public static final String RECEIPT_UNREADABLE = "ENERGY_PARAMETER_PACKAGE_RECEIPT_UNREADABLE";
    public static final String LIST_LIMIT_REACHED = "ENERGY_PARAMETER_PACKAGE_LIST_LIMIT_REACHED";

    private EnergyParameterPackageErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
