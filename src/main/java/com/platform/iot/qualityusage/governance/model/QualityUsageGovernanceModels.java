package com.platform.iot.qualityusage.governance.model;

/**
 * 质量使用治理持久化状态的集中名称。
 *
 * <p>场景编码仍由数据库目录维护，不能在这里收敛为业务枚举；这些枚举只约束设计已经
 * 固定的变更集、版本和审核生命周期，避免字符串状态散落在 Service 中。</p>
 */
public final class QualityUsageGovernanceModels {
    private QualityUsageGovernanceModels() {
    }

    public enum ChangeSetStatus { DRAFT, PENDING, PUBLISHED, CANCELLED }

    public enum PolicyVersionStatus { DRAFT, ACTIVE, RETIRED }

    public enum ReviewStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

    public enum ReviewMode { NORMAL, DIRECT_PUBLISH }

    public enum ScenarioStatus { DRAFT, ENABLED, DISABLED }
}
