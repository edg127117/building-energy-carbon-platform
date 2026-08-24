package com.platform.iot.collection;

import com.platform.framework.exception.BusinessException;

/** 采集治理 API 的稳定机器错误码。 */
public final class CollectionErrors {
    public static final String UNAUTHORIZED = "COLLECTION_CONFIG_UNAUTHORIZED";
    public static final String FORBIDDEN = "COLLECTION_CONFIG_FORBIDDEN";
    public static final String NOT_FOUND = "COLLECTION_CONFIG_NOT_FOUND";
    public static final String VALIDATION_FAILED = "COLLECTION_CONFIG_VALIDATION_FAILED";
    public static final String DUPLICATE = "COLLECTION_CONFIG_DUPLICATE";
    public static final String STATE_CONFLICT = "COLLECTION_CONFIG_STATE_CONFLICT";
    public static final String REFERENCE_CONFLICT = "COLLECTION_CONFIG_REFERENCE_CONFLICT";
    public static final String BUILDING_MISMATCH = "COLLECTION_CONFIG_BUILDING_MISMATCH";
    public static final String VERSION_CONFLICT = "COLLECTION_CONFIG_VERSION_CONFLICT";
    public static final String DRAFT_CONFLICT = "COLLECTION_CONFIG_DRAFT_CONFLICT";
    public static final String REVIEW_CONFLICT = "COLLECTION_CONFIG_REVIEW_CONFLICT";

    private CollectionErrors() {}

    public static BusinessException error(int httpStatus, String errorCode, String message) {
        return new BusinessException(httpStatus, errorCode, message);
    }
}
