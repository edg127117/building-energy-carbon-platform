package com.platform.iot.calculation;

import com.platform.framework.exception.BusinessException;

/** 计算测点公共读取端口对调用方公开的稳定错误码。 */
public final class CalculationPointReadErrors {
    public static final String VALIDATION_FAILED = "CALCULATION_POINT_READ_VALIDATION_FAILED";
    public static final String RESOURCE_LIMIT_EXCEEDED = "CALCULATION_POINT_READ_RESOURCE_LIMIT_EXCEEDED";
    public static final String POINT_METADATA_REQUIRED = "CALCULATION_POINT_READ_POINT_METADATA_REQUIRED";
    public static final String DATA_SCOPE_MISMATCH = "CALCULATION_POINT_READ_DATA_SCOPE_MISMATCH";
    public static final String RAW_FACT_INVALID = "CALCULATION_POINT_READ_RAW_FACT_INVALID";
    public static final String DUPLICATE_FACT_IDENTITY = "CALCULATION_POINT_READ_DUPLICATE_FACT_IDENTITY";
    public static final String READER_PROTOCOL_INVALID = "CALCULATION_POINT_READ_READER_PROTOCOL_INVALID";
    public static final String QUALITY_POLICY_UNAVAILABLE = "CALCULATION_POINT_READ_QUALITY_POLICY_UNAVAILABLE";
    public static final String DEPENDENCY_UNAVAILABLE = "CALCULATION_POINT_READ_DEPENDENCY_UNAVAILABLE";

    private CalculationPointReadErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
