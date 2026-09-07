package com.platform.iot.calculation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 供冷量、累计量和积分计算复用的上游测点事实快照契约。 */
public final class CalculationPointReadContracts {
    /** 当前原始读取端口未提供可审计的数据性质，不能由来源系统名称推断。 */
    public static final String SOURCE_NATURE_UNKNOWN = "UNKNOWN";
    public static final String SOURCE_NATURE_EVIDENCE_UNAVAILABLE = "UPSTREAM_NOT_PROVIDED";

    private CalculationPointReadContracts() {
    }

    /**
     * 固定读取水位和质量策略修订的一次完整、有界事实快照。
     *
     * <p>事实列表保留质量门禁拒绝行，调用方必须据此把积分或累计计算的缺口显式拆开，
     * 不能过滤后连接两个允许样本。</p>
     */
    public record CalculationPointSnapshot(
            String buildingId,
            Instant fromInclusive,
            Instant toInclusive,
            Instant activityWatermark,
            String identityRule,
            String duplicateHandling,
            String correctionEvidence,
            String scenarioCode,
            long qualityConfigRevision,
            int scannedFactCount,
            int filteredAfterWatermarkCount,
            List<PointDescriptor> points,
            List<CalculationPointFact> facts) {
        public CalculationPointSnapshot {
            Objects.requireNonNull(buildingId, "buildingId");
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toInclusive, "toInclusive");
            Objects.requireNonNull(activityWatermark, "activityWatermark");
            Objects.requireNonNull(identityRule, "identityRule");
            Objects.requireNonNull(duplicateHandling, "duplicateHandling");
            Objects.requireNonNull(correctionEvidence, "correctionEvidence");
            Objects.requireNonNull(scenarioCode, "scenarioCode");
            points = List.copyOf(points);
            facts = List.copyOf(facts);
        }
    }

    /** 一个请求内已核验建筑归属、类型和原始单位的测点档案。 */
    public record PointDescriptor(
            String pointId,
            String pointCode,
            String dataType,
            String unit) {
        public PointDescriptor {
            Objects.requireNonNull(pointId, "pointId");
            Objects.requireNonNull(dataType, "dataType");
            Objects.requireNonNull(unit, "unit");
        }
    }

    /**
     * 一条原始事实及其不可省略的质量使用策略证据。
     *
     * <p>{@code factIdentity} 固定为 {@code pointId:eventTimeEpochMillis}，与原始事实的
     * {@code POINT_ID_EVENT_TIME} 身份规则及累计量修正入口一致。</p>
     */
    public record CalculationPointFact(
            String pointId,
            String factIdentity,
            String pointCode,
            String dataType,
            String unit,
            String sourceSystem,
            String sourcePointCode,
            String sourceDeviceId,
            String sourceNature,
            String sourceNatureEvidence,
            double rawValue,
            Instant eventTime,
            Instant receivedTime,
            String qualityLevel,
            String decision,
            String policySource,
            Integer policyVersion,
            long policyConfigRevision,
            String policyReason) {
        public CalculationPointFact {
            Objects.requireNonNull(pointId, "pointId");
            Objects.requireNonNull(factIdentity, "factIdentity");
            Objects.requireNonNull(dataType, "dataType");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(sourceNature, "sourceNature");
            Objects.requireNonNull(sourceNatureEvidence, "sourceNatureEvidence");
            if (!Double.isFinite(rawValue)) {
                throw new IllegalArgumentException("rawValue must be finite");
            }
            Objects.requireNonNull(eventTime, "eventTime");
            Objects.requireNonNull(receivedTime, "receivedTime");
            Objects.requireNonNull(qualityLevel, "qualityLevel");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(policySource, "policySource");
            Objects.requireNonNull(policyReason, "policyReason");
        }
    }
}
