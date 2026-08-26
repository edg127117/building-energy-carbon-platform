package com.platform.audit;

/** 审计治理使用的部署环境语义；它独立于 Spring Profile，避免生产自审由模糊配置决定。 */
public enum AuditEnvironmentMode {
    DEVELOPMENT,
    TEST,
    PRODUCTION
}
