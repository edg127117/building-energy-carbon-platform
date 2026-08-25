package com.platform.iot.qualityusage;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 质量事实消费门禁共用的稳定领域模型。
 *
 * <p>场景编码来自 MySQL 目录而不是封闭枚举；这里的三个常量只标识首版已经接入的
 * 消费适配器。允许等级使用显式集合，空集合因此可以与“没有正式策略”清晰区分。</p>
 */
public final class QualityUsageModels {
    public static final String POINT_REALTIME_VIEW = "POINT_REALTIME_VIEW";
    public static final String POINT_HISTORY_VIEW = "POINT_HISTORY_VIEW";
    public static final String INDICATOR_CALCULATION = "INDICATOR_CALCULATION";

    private QualityUsageModels() {
    }

    public enum QualityLevel {
        Q0(0), Q1(1), Q2(2);

        private final int code;

        QualityLevel(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static QualityLevel fromCode(int code) {
            return switch (code) {
                case 0 -> Q0;
                case 1 -> Q1;
                case 2 -> Q2;
                default -> throw new IllegalArgumentException("Unsupported quality level: " + code);
            };
        }
    }

    public enum Decision { ALLOW, BLOCK }

    public enum UsageStatus {
        AVAILABLE, QUALITY_BLOCKED, POLICY_SNAPSHOT_UNAVAILABLE
    }

    public enum PolicySource { PUBLISHED_POLICY, SYSTEM_DEFAULT_Q0_ONLY }

    public enum RuntimeApplyStatus { PENDING_REFRESH, APPLIED, REFRESH_FAILED }

    public record Resolution(
            Decision decision,
            int actualQuality,
            String scenarioCode,
            PolicySource policySource,
            Integer policyVersion,
            long configRevision,
            String reason) {
        public Resolution {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(scenarioCode, "scenarioCode");
            Objects.requireNonNull(policySource, "policySource");
            Objects.requireNonNull(reason, "reason");
        }

        public UsageStatus usageStatus() {
            return decision == Decision.ALLOW
                    ? UsageStatus.AVAILABLE : UsageStatus.QUALITY_BLOCKED;
        }
    }

    /** 一个策略版本的半开生效区间；空 allowedQualities 表示显式完全禁止。 */
    public record PolicyInterval(
            String policyId,
            int versionNo,
            Long effectiveFromMs,
            Long effectiveToMs,
            Set<QualityLevel> allowedQualities) {
        public PolicyInterval {
            Objects.requireNonNull(policyId, "policyId");
            allowedQualities = Set.copyOf(allowedQualities);
        }

        public boolean contains(long minuteStart) {
            return (effectiveFromMs == null || minuteStart >= effectiveFromMs)
                    && (effectiveToMs == null || minuteStart < effectiveToMs);
        }
    }

    public record Scenario(String code, String adapterType, String status) {
        public Scenario {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(adapterType, "adapterType");
            Objects.requireNonNull(status, "status");
        }

        public boolean enabled() {
            return "ENABLED".equals(status);
        }
    }

    public record PolicyKey(String pointId, String scenarioCode) {
        public PolicyKey {
            Objects.requireNonNull(pointId, "pointId");
            Objects.requireNonNull(scenarioCode, "scenarioCode");
        }
    }

    /**
     * 消费模块可持有的单次解析上下文，只公开配置修订号，不公开策略索引。
     *
     * <p>同一 HTTP 响应、WebSocket 事件或公式计算复用一个上下文，既保证批内决策一致，
     * 又避免调用方依赖当前内存 Map 的组织方式。</p>
     */
    public static final class ResolutionContext {
        private final RuntimeSnapshot snapshot;

        ResolutionContext(RuntimeSnapshot snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }

        RuntimeSnapshot snapshot() {
            return snapshot;
        }

        public long configRevision() {
            return snapshot.revision();
        }
    }

    /** 仅供质量使用策略包内部加载、刷新和解析的不可变索引。 */
    static final class RuntimeSnapshot {
        private final long revision;
        private final java.util.Map<String, Scenario> scenarios;
        private final java.util.Map<PolicyKey, List<PolicyInterval>> policies;

        RuntimeSnapshot(
                long revision,
                java.util.Map<String, Scenario> scenarios,
                java.util.Map<PolicyKey, List<PolicyInterval>> policies) {
            this.revision = revision;
            this.scenarios = java.util.Map.copyOf(scenarios);
            this.policies = policies.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            java.util.Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
        }

        long revision() {
            return revision;
        }

        java.util.Map<String, Scenario> scenarios() {
            return scenarios;
        }

        java.util.Map<PolicyKey, List<PolicyInterval>> policies() {
            return policies;
        }
    }
}
