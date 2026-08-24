package com.platform.iot.qualityusage;

import com.platform.framework.exception.BusinessException;

/** 质量使用治理与运行门禁的稳定机器错误。 */
public final class QualityUsageErrors {
    public static final String VALIDATION_FAILED = "QUALITY_POLICY_VALIDATION_FAILED";
    public static final String UNAUTHORIZED = "QUALITY_POLICY_UNAUTHORIZED";
    public static final String FORBIDDEN = "QUALITY_POLICY_FORBIDDEN";
    public static final String NOT_FOUND = "QUALITY_POLICY_NOT_FOUND";
    public static final String STATE_CONFLICT = "QUALITY_POLICY_STATE_CONFLICT";
    public static final String VERSION_CONFLICT = "QUALITY_POLICY_VERSION_CONFLICT";
    public static final String PENDING_CONFLICT = "QUALITY_POLICY_PENDING_CONFLICT";
    public static final String POINT_NOT_ELIGIBLE = "QUALITY_POLICY_POINT_NOT_ELIGIBLE";
    public static final String SCENARIO_DISABLED = "QUALITY_USAGE_SCENARIO_DISABLED";
    public static final String SNAPSHOT_UNAVAILABLE = "QUALITY_POLICY_SNAPSHOT_UNAVAILABLE";
    public static final String RECOVERY_REQUIRED = "QUALITY_POLICY_RECOVERY_REQUIRED";
    public static final String IDEMPOTENCY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    private QualityUsageErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
