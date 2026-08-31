package com.platform.iot.energymetadata;

import com.platform.framework.exception.BusinessException;

/** 能源测点属性 API 的稳定机器错误码。 */
public final class EnergyMetadataErrors {
    public static final String UNAUTHORIZED = "ENERGY_METADATA_UNAUTHORIZED";
    public static final String FORBIDDEN = "ENERGY_METADATA_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_METADATA_VALIDATION_FAILED";
    public static final String NOT_FOUND = "ENERGY_METADATA_NOT_FOUND";
    public static final String POINT_NOT_FOUND = "ENERGY_METADATA_POINT_NOT_FOUND";
    public static final String BUILDING_MISMATCH = "ENERGY_METADATA_BUILDING_MISMATCH";
    public static final String DUPLICATE = "ENERGY_METADATA_DUPLICATE";
    public static final String VERSION_CONFLICT = "ENERGY_METADATA_VERSION_CONFLICT";
    public static final String UNIT_UNSUPPORTED = "ENERGY_METADATA_UNIT_UNSUPPORTED";
    public static final String REFERENCE_CONFLICT = "ENERGY_METADATA_REFERENCE_CONFLICT";

    private EnergyMetadataErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
