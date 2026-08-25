package com.platform.iot.deviceparameter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 设备参数治理在 Service、Repository 和公式解析边界之间使用的不可变领域值。 */
public final class DeviceParameterModels {
    private DeviceParameterModels() {
    }

    /** LEGACY_MIGRATION 只用于一次性旧值证据，不对外开放为日常第五种来源入口。 */
    public enum SourceType { DEVICE, MANUAL, EXCEL, TEMPLATE, LEGACY_MIGRATION }

    public enum ValidationStatus { READY, UNMAPPED, INVALID }

    public enum ConflictStatus { CONSISTENT, CONFLICTING, UNMAPPED, INVALID, RESOLVED }

    public enum VersionStatus { DRAFT, PENDING_REVIEW, PUBLISHED, REJECTED, CANCELLED }

    public enum ChangeType { INITIAL, UPDATE, ROLLBACK, CLEAR, MIGRATION }

    public enum PublishType { IMMEDIATE, RETROACTIVE }

    public enum RecalculationStatus {
        NOT_REQUIRED, PENDING_RECALC, RECALCULATING, SUCCEEDED, RECALC_FAILED
    }

    public record EquipmentIdentity(
            String equipmentId,
            String equipmentCode,
            String equipmentTypeCode,
            String buildingId,
            String productId) {
    }

    public record Definition(
            String definitionId,
            String parameterCode,
            String parameterName,
            String businessDefinition,
            String quantityKind,
            String standardUnit,
            int storageScale,
            int displayScale,
            String evidenceReference,
            String status,
            int configRevision) {
    }

    public record Applicability(
            String applicabilityId,
            String equipmentTypeCode,
            String definitionId,
            boolean required,
            boolean formulaReadable,
            BigDecimal hardMin,
            BigDecimal hardMax,
            BigDecimal warningMin,
            BigDecimal warningMax,
            BigDecimal comparisonTolerance,
            String evidenceReference,
            String status,
            int configRevision) {
    }

    public record Candidate(
            String candidateId,
            String buildingId,
            String equipmentId,
            String definitionId,
            String parameterCode,
            SourceType sourceType,
            String sourceReference,
            String sourceVersion,
            String sourceParameterKey,
            String rawValue,
            String rawUnit,
            BigDecimal normalizedValue,
            String standardUnit,
            String mappingVersionId,
            LocalDateTime observedAt,
            ValidationStatus validationStatus,
            String validationReason,
            boolean warning,
            boolean current,
            String payloadHash,
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt) {
    }

    public record Conflict(
            String conflictId,
            String equipmentId,
            String definitionId,
            ConflictStatus status,
            int configRevision,
            String selectedCandidateId,
            String resolutionReason,
            List<Candidate> members) {
    }

    public record VersionValue(
            String definitionId,
            String parameterCode,
            String valueStatus,
            BigDecimal value,
            String standardUnit,
            String candidateId,
            SourceType sourceType,
            String sourceReference,
            String sourceVersion,
            LocalDateTime observedAt,
            String missingReason,
            String warningReason) {
    }

    public record ParameterVersion(
            String versionId,
            String parameterSetId,
            int versionNo,
            VersionStatus status,
            int configRevision,
            Integer submittedRevision,
            String baseVersionId,
            String baseTimelineRevisionId,
            String copiedFromVersionId,
            ChangeType changeType,
            LocalDateTime requestedEffectiveFrom,
            LocalDateTime requestedEffectiveTo,
            String changeReason,
            String evidenceReference,
            Long ownerUserId,
            Long rollbackInitiatorId,
            LocalDateTime publishedAt,
            List<VersionValue> values) {
    }

    public record TimelineSegment(
            LocalDateTime businessEffectiveFrom,
            LocalDateTime businessEffectiveTo,
            String versionId) {
    }

    public record TimelineRevision(
            String timelineRevisionId,
            String parameterSetId,
            int revisionNo,
            LocalDateTime publishedAt,
            Long publishedBy,
            String reviewRequestId,
            PublishType publishType,
            String retroactiveReason,
            String evidenceReference,
            RecalculationStatus recalculationStatus,
            List<TimelineSegment> segments) {
    }

    public record ResolvedParameter(
            String parameterCode,
            BigDecimal value,
            String standardUnit,
            String definitionId,
            String versionId,
            String timelineRevisionId,
            SourceType sourceType,
            String sourceReference,
            LocalDateTime observedAt,
            LocalDateTime businessEffectiveFrom,
            LocalDateTime publishedAt) {
    }

    public record ResolvedParameters(
            String equipmentId,
            LocalDateTime businessTime,
            LocalDateTime knowledgeTime,
            String versionId,
            String timelineRevisionId,
            Map<String, ResolvedParameter> values) {

        public ResolvedParameters {
            values = Map.copyOf(values);
        }
    }

    public record Impact(
            String equipmentId,
            LocalDateTime from,
            LocalDateTime to,
            List<String> parameterCodes,
            List<String> indicatorIds,
            long affectedMinuteCount,
            String baselineTimelineRevisionId,
            String fingerprint) {
    }
}
