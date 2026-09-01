package com.platform.energy.catalog;

import com.platform.framework.exception.BusinessException;

/** 能源字典治理 API 的稳定机器错误码。 */
public final class EnergyCatalogErrors {
    public static final String UNAUTHORIZED = "ENERGY_CATALOG_UNAUTHORIZED";
    public static final String FORBIDDEN = "ENERGY_CATALOG_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_CATALOG_VALIDATION_FAILED";
    public static final String NOT_FOUND = "ENERGY_CATALOG_NOT_FOUND";
    public static final String VERSION_CONFLICT = "ENERGY_CATALOG_VERSION_CONFLICT";
    public static final String STATUS_CONFLICT = "ENERGY_CATALOG_STATUS_CONFLICT";
    public static final String BUILDING_MISMATCH = "ENERGY_CATALOG_BUILDING_MISMATCH";
    public static final String POINT_PROFILE_REQUIRED = "ENERGY_CATALOG_POINT_PROFILE_REQUIRED";
    public static final String ITEM_CATEGORY_CONFLICT = "ENERGY_CATALOG_ITEM_CATEGORY_CONFLICT";
    public static final String UNIT_INCOMPATIBLE = "ENERGY_CATALOG_UNIT_INCOMPATIBLE";
    public static final String MOBILE_SCOPE_REJECTED = "ENERGY_CATALOG_MOBILE_SCOPE_REJECTED";
    public static final String EFFECTIVE_TIME_CONFLICT = "ENERGY_CATALOG_EFFECTIVE_TIME_CONFLICT";

    private EnergyCatalogErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
