package com.platform.audit.sensitive;

/** 通用敏感变更状态；领域自有审核闭环不使用该状态机。 */
public enum SensitiveChangeStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    EXECUTED,
    REJECTED,
    WITHDRAWN,
    EXECUTION_FAILED
}
