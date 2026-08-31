package com.platform.relation;

import com.platform.framework.exception.BusinessException;

/** 关系治理 API 对外稳定的机器错误码。 */
public final class RelationErrors {
    public static final String UNAUTHORIZED = "RELATION_UNAUTHORIZED";
    public static final String FORBIDDEN = "RELATION_FORBIDDEN";
    public static final String NOT_FOUND = "RELATION_NOT_FOUND";
    public static final String VALIDATION_FAILED = "RELATION_VALIDATION_FAILED";
    public static final String VERSION_CONFLICT = "RELATION_VERSION_CONFLICT";
    public static final String REVIEW_CONFLICT = "RELATION_REVIEW_CONFLICT";
    public static final String CROSS_BUILDING = "RELATION_CROSS_BUILDING";
    public static final String CYCLE_DETECTED = "RELATION_CYCLE_DETECTED";
    public static final String PENDING_EXPERT = "RELATION_PENDING_EXPERT";
    public static final String REFERENCE_CONFLICT = "RELATION_REFERENCE_CONFLICT";
    public static final String GOVERNANCE_REQUIRED = "RELATION_GOVERNANCE_REQUIRED";
    public static final String SCOPE_UNSUPPORTED = "RELATION_SCOPE_UNSUPPORTED";
    public static final String UNASSIGNED = "RELATION_UNASSIGNED";
    public static final String IDEMPOTENCY_REUSED = "RELATION_IDEMPOTENCY_REUSED";
    public static final String IMPORT_REJECTED = "RELATION_METERING_IMPORT_REJECTED";
    public static final String IMPORT_TEMPLATE_UNSUPPORTED = "RELATION_METERING_TEMPLATE_UNSUPPORTED";
    public static final String IMPORT_HEADER_INVALID = "RELATION_METERING_IMPORT_HEADER_INVALID";

    private RelationErrors() {
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
