package com.platform.energy.summary;

import com.platform.framework.exception.BusinessException;

/** 计量边界汇总和多维查询 API 使用的稳定错误码。 */
public final class EnergySummaryErrors {
    public static final String UNAUTHORIZED = "ENERGY_SUMMARY_UNAUTHORIZED";
    public static final String FORBIDDEN = "ENERGY_SUMMARY_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_SUMMARY_VALIDATION_FAILED";
    public static final String NOT_FOUND = "ENERGY_SUMMARY_NOT_FOUND";
    public static final String POLICY_REQUIRED = "ENERGY_SUMMARY_POLICY_REQUIRED";
    public static final String RELATION_UNCONFIRMED = "ENERGY_SUMMARY_RELATION_UNCONFIRMED";
    public static final String RESULT_LIMIT_EXCEEDED = "ENERGY_SUMMARY_RESULT_LIMIT_EXCEEDED";
    public static final String VERSION_CONFLICT = "ENERGY_SUMMARY_VERSION_CONFLICT";

    private EnergySummaryErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
