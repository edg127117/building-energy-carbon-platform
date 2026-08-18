package com.platform.hvac.asset.service;

import com.platform.framework.exception.BusinessException;

/** 资产管理 V1 对外稳定错误码。 */
public final class AssetErrors {
    public static final String NOT_FOUND = "ASSET_NOT_FOUND";
    public static final String VALIDATION_FAILED = "ASSET_VALIDATION_FAILED";
    public static final String REFERENCE_CONFLICT = "ASSET_REFERENCE_CONFLICT";
    public static final String STATE_CONFLICT = "ASSET_STATE_CONFLICT";

    private AssetErrors() {
    }

    public static BusinessException error(int status, String errorCode, String message) {
        return new BusinessException(status, errorCode, message);
    }
}
