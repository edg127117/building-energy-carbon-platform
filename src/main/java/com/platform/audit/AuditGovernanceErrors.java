package com.platform.audit;

import com.platform.framework.exception.BusinessException;

/** 公共审计治理错误码。领域模块仍保留自己的业务错误码。 */
public final class AuditGovernanceErrors {
    public static final String OPERATION_FORBIDDEN = "BACKOFFICE_OPERATION_FORBIDDEN";
    public static final String DUTY_REQUIRED = "BACKOFFICE_DUTY_REQUIRED";
    public static final String SELF_APPROVAL_DENIED = "BACKOFFICE_SELF_APPROVAL_DENIED";
    public static final String REQUEST_CONFLICT = "BACKOFFICE_REQUEST_CONFLICT";
    public static final String REQUEST_HASH_MISMATCH = "BACKOFFICE_REQUEST_HASH_MISMATCH";

    private AuditGovernanceErrors() {
    }

    public static BusinessException forbidden(String errorCode) {
        return new BusinessException(403, errorCode, "无权限执行该操作");
    }

    public static BusinessException conflict(String errorCode, String message) {
        return new BusinessException(409, errorCode, message);
    }
}
