package com.platform.audit;

import com.platform.framework.exception.BusinessException;

/** 公共审计治理错误码。领域模块仍保留自己的业务错误码。 */
public final class AuditGovernanceErrors {
    public static final String OPERATION_FORBIDDEN = "BACKOFFICE_OPERATION_FORBIDDEN";
    public static final String DUTY_REQUIRED = "BACKOFFICE_DUTY_REQUIRED";
    public static final String SELF_APPROVAL_DENIED = "BACKOFFICE_SELF_APPROVAL_DENIED";
    public static final String REQUEST_CONFLICT = "BACKOFFICE_REQUEST_CONFLICT";
    public static final String REQUEST_HASH_MISMATCH = "BACKOFFICE_REQUEST_HASH_MISMATCH";
    public static final String REVIEW_REQUIRED = "BACKOFFICE_REVIEW_REQUIRED";
    public static final String QUERY_FORBIDDEN = "AUDIT_QUERY_FORBIDDEN";
    public static final String QUERY_INVALID = "AUDIT_QUERY_INVALID";
    public static final String EXPORT_FORBIDDEN = "AUDIT_EXPORT_FORBIDDEN";
    public static final String EXPORT_PURPOSE_INVALID = "AUDIT_EXPORT_PURPOSE_INVALID";
    public static final String EXPORT_LIMIT_EXCEEDED = "AUDIT_EXPORT_LIMIT_EXCEEDED";
    public static final String EXPORT_NOT_READY = "AUDIT_EXPORT_NOT_READY";
    public static final String EXPORT_QUEUE_FULL = "AUDIT_EXPORT_QUEUE_FULL";
    public static final String RETENTION_POLICY_INVALID = "AUDIT_RETENTION_POLICY_INVALID";
    public static final String EVIDENCE_HOLD_INVALID = "AUDIT_EVIDENCE_HOLD_INVALID";
    public static final String EVIDENCE_HOLD_CONFLICT = "AUDIT_EVIDENCE_HOLD_CONFLICT";
    public static final String CLEANUP_SCOPE_CHANGED = "AUDIT_CLEANUP_SCOPE_CHANGED";

    private AuditGovernanceErrors() {
    }

    public static BusinessException forbidden(String errorCode) {
        return new BusinessException(403, errorCode, "无权限执行该操作");
    }

    public static BusinessException conflict(String errorCode, String message) {
        return new BusinessException(409, errorCode, message);
    }

    public static BusinessException invalidQuery(String message) {
        return new BusinessException(400, QUERY_INVALID, message);
    }

    /** 旧直改入口关闭后返回稳定机器码，调用方必须改用通用敏感变更申请。 */
    public static BusinessException reviewRequired() {
        return conflict(REVIEW_REQUIRED, "该敏感操作必须通过后台变更申请审核后执行");
    }
}
