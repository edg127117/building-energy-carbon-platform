package com.platform.iot.collection.model;

/** 数据源与采集策略治理使用的稳定领域枚举。 */
public final class CollectionModels {
    private CollectionModels() {}

    public enum SourceStatus { DRAFT, ENABLED, DISABLED }
    public enum AliasStatus { DRAFT, ENABLED, DISABLED }
    public enum PolicyVersionStatus { DRAFT, ACTIVE, RETIRED }
    public enum ReviewStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }
    public enum ReviewTargetType { SOURCE_ACTIVATION, ALIAS_ACTIVATION, POLICY_VERSION }
    public enum RetentionMode { FIXED_DAYS, LONG_TERM }
    public enum ChangeType { CREATE, UPDATE, DISABLE, ROLLBACK, INITIAL_MIGRATION }
    public enum ChangeSource { MANUAL, INITIAL_MIGRATION }
    public enum RuntimeApplyStatus { NOT_APPLICABLE, PENDING_REFRESH, APPLIED, REFRESH_FAILED }
}
