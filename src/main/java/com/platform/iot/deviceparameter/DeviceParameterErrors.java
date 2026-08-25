package com.platform.iot.deviceparameter;

import com.platform.framework.exception.BusinessException;

/** 设备参数治理对外稳定错误码。 */
public final class DeviceParameterErrors {
    public static final String VALIDATION_FAILED = "DEVICE_PARAMETER_VALIDATION_FAILED";
    public static final String DEFINITION_NOT_FOUND = "DEVICE_PARAMETER_DEFINITION_NOT_FOUND";
    public static final String MAPPING_NOT_FOUND = "DEVICE_PARAMETER_MAPPING_NOT_FOUND";
    public static final String UNIT_INCOMPATIBLE = "DEVICE_PARAMETER_UNIT_INCOMPATIBLE";
    public static final String PRECISION_INVALID = "DEVICE_PARAMETER_PRECISION_INVALID";
    public static final String VALUE_OUT_OF_RANGE = "DEVICE_PARAMETER_VALUE_OUT_OF_RANGE";
    public static final String CANDIDATE_CONFLICT = "DEVICE_PARAMETER_CANDIDATE_CONFLICT";
    public static final String DRAFT_CONFLICT = "DEVICE_PARAMETER_DRAFT_CONFLICT";
    public static final String VERSION_CONFLICT = "DEVICE_PARAMETER_VERSION_CONFLICT";
    public static final String REVIEW_CONFLICT = "DEVICE_PARAMETER_REVIEW_CONFLICT";
    public static final String NO_EFFECTIVE_VERSION = "DEVICE_PARAMETER_NO_EFFECTIVE_VERSION";
    public static final String RETROACTIVE_APPROVAL_REQUIRED =
            "DEVICE_PARAMETER_RETROACTIVE_APPROVAL_REQUIRED";
    public static final String TIMELINE_OVERLAP = "DEVICE_PARAMETER_TIMELINE_OVERLAP";
    public static final String RECALCULATION_PENDING = "DEVICE_PARAMETER_RECALCULATION_PENDING";
    public static final String RECALCULATION_FAILED = "DEVICE_PARAMETER_RECALCULATION_FAILED";
    public static final String IMPORT_REJECTED = "DEVICE_PARAMETER_IMPORT_REJECTED";
    public static final String LEGACY_MAPPING_MISSING = "DEVICE_PARAMETER_LEGACY_MAPPING_MISSING";
    public static final String FORBIDDEN = "DEVICE_PARAMETER_FORBIDDEN";
    public static final String NOT_FOUND = "DEVICE_PARAMETER_NOT_FOUND";
    public static final String LEGACY_WRITE_FORBIDDEN = "DEVICE_PARAMETER_LEGACY_WRITE_FORBIDDEN";

    private DeviceParameterErrors() {
    }

    public static BusinessException error(int httpStatus, String code, String message) {
        return new BusinessException(httpStatus, code, message);
    }
}
