package com.platform.iot.deviceparameter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ConflictHead;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ParameterSetHead;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ReviewHead;
import com.platform.iot.deviceparameter.DeviceParameterModels.Applicability;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.ChangeType;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.Impact;
import com.platform.iot.deviceparameter.DeviceParameterModels.ParameterVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.PublishType;
import com.platform.iot.deviceparameter.DeviceParameterModels.RecalculationStatus;
import com.platform.iot.deviceparameter.DeviceParameterModels.TimelineRevision;
import com.platform.iot.deviceparameter.DeviceParameterModels.TimelineSegment;
import com.platform.iot.deviceparameter.DeviceParameterModels.ValidationStatus;
import com.platform.iot.deviceparameter.DeviceParameterModels.VersionStatus;
import com.platform.iot.deviceparameter.DeviceParameterModels.VersionValue;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DifferenceView;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.EffectiveParameterView;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DraftCreateRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DraftUpdateRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DraftValueRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ReviewDecisionRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ReviewView;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.RollbackRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.SubmitRequest;
import com.platform.iot.deviceparameter.formula.DeviceParameterFormulaImpactPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.invalid;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.json;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.now;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.sha256;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 单台设备完整参数快照的草稿、审核、双时间发布、追溯、差异和平台回退服务。
 *
 * <p>版本内容发布后不可修改；当前有效状态由不可变时间线修订推导。审批事务同时锁定参数集、
 * 版本、审核请求和当前时间线，职责分离、基线修订与影响指纹任一失配都会整体回滚。</p>
 */
public class DeviceParameterGovernanceService {
    private static final int MAX_RECALC_MINUTES = 525_600;

    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterAuthorization authorization;
    private final DeviceParameterFormulaImpactPort formulaImpactPort;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final DeviceParameterLegacyCompatibilityService legacyCompatibilityService;

    @Transactional
    public ParameterVersion createDraft(
            long userId, Collection<String> roles, String equipmentId,
            DraftCreateRequest request) {
        authorization.requireMaintainer(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        ParameterSetHead set = repository.findParameterSet(equipmentId, true).orElse(null);
        if (set == null) {
            repository.insertParameterSet(id(), equipment);
            set = repository.findParameterSet(equipmentId, true).orElseThrow();
        }
        if (repository.findOpenVersion(set.parameterSetId(), false).isPresent()) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.DRAFT_CONFLICT,
                    "该设备已有未完成参数草稿");
        }
        ChangeType changeType = ChangeType.valueOf(request.changeType());
        ParameterVersion base = effectiveVersion(set, now()).orElse(null);
        if (base == null && changeType != ChangeType.INITIAL) {
            throw invalid("没有正式版本时只能创建 INITIAL 草稿");
        }
        if (base != null && changeType == ChangeType.INITIAL) {
            throw invalid("已有正式版本时不能再创建 INITIAL 草稿");
        }
        List<VersionValue> values = base == null
                ? initialMissingValues(equipment)
                : copyValues(base.values());
        ParameterVersion draft = new ParameterVersion(id(), set.parameterSetId(),
                repository.nextVersionNo(set.parameterSetId()), VersionStatus.DRAFT, 0,
                null, base == null ? null : base.versionId(), set.currentTimelineRevisionId(),
                null, changeType, null, null, request.changeReason().trim(),
                request.evidenceReference().trim(), userId, null, null, values);
        repository.insertVersion(draft);
        repository.insertVersionValues(draft.versionId(), values);
        audit(equipment.buildingId(), userId, "CREATE_DRAFT", "VERSION",
                draft.versionId(), draft.versionId(), null, request.changeReason());
        return repository.findVersion(draft.versionId(), false).orElseThrow();
    }

    /**
     * 用已确认旧字段映射产生的候选建立 MIGRATION 草稿；缺少任一必填参数时拒绝建草稿。
     * 该入口不暴露为 HTTP API，迁移值仍须提交给另一账号审核后才可能成为 V1。
     */
    @Transactional
    public ParameterVersion createMigrationDraft(
            long userId, Collection<String> roles, String equipmentId,
            List<String> candidateIds, String batchId, String evidenceReference) {
        authorization.requireGlobalConfigurator(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        ParameterSetHead set = repository.findParameterSet(equipmentId, true).orElse(null);
        if (set == null) {
            repository.insertParameterSet(id(), equipment);
            set = repository.findParameterSet(equipmentId, true).orElseThrow();
        }
        ParameterVersion existing = repository.findOpenVersion(set.parameterSetId(), false)
                .orElse(null);
        if (existing != null) {
            if (existing.changeType() == ChangeType.MIGRATION) {
                return existing;
            }
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.DRAFT_CONFLICT,
                    "该设备已有非迁移参数草稿");
        }
        if (set.currentTimelineRevisionId() != null) {
            throw stateConflict("已有正式参数时间线的设备不能再创建初始迁移草稿");
        }
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (String candidateId : candidateIds) {
            Candidate candidate = repository.findCandidate(candidateId)
                    .orElseThrow(() -> invalid("遗留迁移候选不存在"));
            if (candidate.sourceType() != DeviceParameterModels.SourceType.LEGACY_MIGRATION
                    || !candidate.equipmentId().equals(equipmentId)
                    || candidate.validationStatus() != ValidationStatus.READY) {
                throw invalid("遗留迁移草稿只能引用同一设备的 READY 迁移候选");
            }
            if (candidates.putIfAbsent(candidate.definitionId(), candidate) != null) {
                throw invalid("同一标准参数存在多个遗留迁移候选");
            }
        }
        List<DraftValueRequest> requests = repository.listApplicabilities(
                        equipment.equipmentTypeCode(), true).stream()
                .map(applicability -> migrationDraftValue(applicability, candidates))
                .toList();
        List<VersionValue> values = normalizeDraftValues(equipment, requests);
        ParameterVersion draft = new ParameterVersion(id(), set.parameterSetId(),
                repository.nextVersionNo(set.parameterSetId()), VersionStatus.DRAFT, 0,
                null, null, null, null, ChangeType.MIGRATION, null, null,
                "遗留参数批次 " + batchId + " 建立待审核初始草稿", evidenceReference,
                userId, null, null, values);
        repository.insertVersion(draft);
        repository.insertVersionValues(draft.versionId(), values);
        audit(equipment.buildingId(), userId, "CREATE_MIGRATION_DRAFT", "VERSION",
                draft.versionId(), draft.versionId(), null, batchId);
        return repository.findVersion(draft.versionId(), false).orElseThrow();
    }

    private DraftValueRequest migrationDraftValue(
            Applicability applicability, Map<String, Candidate> candidates) {
        Candidate candidate = candidates.get(applicability.definitionId());
        if (candidate == null) {
            if (applicability.required()) {
                throw invalid("遗留迁移缺少必填参数的已确认映射和值");
            }
            return new DraftValueRequest(applicability.definitionId(), null,
                    "NOT_CONFIGURED", "LEGACY_VALUE_NOT_AVAILABLE", null);
        }
        return new DraftValueRequest(applicability.definitionId(), candidate.candidateId(),
                "VALUE", null, candidate.warning()
                ? "LEGACY_MIGRATION_REQUIRES_PROFESSIONAL_REVIEW" : null);
    }

    @Transactional
    public ParameterVersion updateDraft(
            long userId, Collection<String> roles, String versionId,
            DraftUpdateRequest request) {
        authorization.requireMaintainer(roles);
        ParameterVersion draft = requireVersion(versionId, true);
        ParameterSetHead set = requireSet(draft.parameterSetId(), false);
        authorization.checkBuilding(userId, roles, set.buildingId());
        requireOwner(userId, draft);
        if (draft.status() != VersionStatus.DRAFT) {
            throw stateConflict("只有 DRAFT 版本可以修改");
        }
        EquipmentIdentity equipment = requireEquipment(set.equipmentId());
        List<VersionValue> values = normalizeDraftValues(equipment, request.values());
        int changed = repository.replaceDraftValues(versionId, request.expectedRevision(), values);
        if (changed != 1) {
            throw stateConflict("草稿修订号已过期或状态已变化");
        }
        audit(set.buildingId(), userId, "UPDATE_DRAFT", "VERSION", versionId,
                versionId, null, "revision=" + (request.expectedRevision() + 1));
        return repository.findVersion(versionId, false).orElseThrow();
    }

    @Transactional
    public ReviewView submit(
            long userId, Collection<String> roles, String versionId,
            String idempotencyKey, SubmitRequest request) {
        authorization.requireMaintainer(roles);
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        ReviewHead repeated = repository.findReviewByIdempotency(normalizedKey).orElse(null);
        String requestHash = sha256(versionId + '|' + request.expectedRevision() + '|'
                + request.comment().trim());
        if (repeated != null) {
            if (!requestHash.equals(repeated.requestSha256())) {
                throw stateConflict("同一幂等键对应了不同提交请求");
            }
            return reviewView(repeated, userId, roles);
        }
        ParameterVersion draft = requireVersion(versionId, true);
        ParameterSetHead set = requireSet(draft.parameterSetId(), true);
        authorization.checkBuilding(userId, roles, set.buildingId());
        requireOwner(userId, draft);
        if (draft.status() != VersionStatus.DRAFT
                || draft.configRevision() != request.expectedRevision()) {
            throw stateConflict("草稿状态或修订号已变化");
        }
        EquipmentIdentity equipment = requireEquipment(set.equipmentId());
        validateCompleteSnapshot(equipment, draft.values());
        if (!Objects.equals(draft.baseTimelineRevisionId(), set.currentTimelineRevisionId())) {
            throw stateConflict("草稿基线时间线已过期，请重新创建草稿");
        }
        String snapshot = json(objectMapper, draft);
        String snapshotHash = sha256(snapshot);
        ReviewHead review = new ReviewHead(id(), set.buildingId(), versionId,
                repository.nextReviewNo(versionId), "PENDING", draft.configRevision(),
                snapshotHash, userId, now(), null, null, null, normalizedKey, requestHash,
                null, null);
        if (repository.markVersionPending(versionId, draft.configRevision()) != 1) {
            throw stateConflict("草稿并发提交失败");
        }
        repository.insertReview(review, snapshot);
        audit(set.buildingId(), userId, "SUBMIT_REVIEW", "REVIEW", review.requestId(),
                versionId, null, snapshotHash);
        return reviewView(review, userId, roles);
    }

    public Impact previewRetroactiveImpact(
            long userId, Collection<String> roles, String equipmentId, String versionId,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        authorization.requireReviewer(roles);
        authorization.requireRetroactiveApprover(roles);
        ParameterVersion version = requireVersion(versionId, false);
        ParameterSetHead set = requireSet(version.parameterSetId(), false);
        authorization.checkBuilding(userId, roles, set.buildingId());
        if (!Objects.equals(equipmentId, set.equipmentId())) {
            throw invalid("追溯影响预览的版本不属于路径指定设备");
        }
        LocalDateTime current = now();
        Range range = validateRetroactiveRange(effectiveFrom, effectiveTo, current,
                repository.findCurrentTimeline(set.parameterSetId(), false).orElse(null));
        List<String> changedCodes = changedParameterCodes(version);
        List<String> indicatorIds = formulaImpactPort.affectedIndicatorIds(
                set.equipmentId(), changedCodes, range.from(), range.recalcTo());
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(
                floorMinute(range.from()), ceilMinute(range.recalcTo())));
        long affected = Math.multiplyExact(minutes, indicatorIds.size());
        String fingerprint = impactFingerprint(version, set, range, changedCodes, indicatorIds);
        return new Impact(set.equipmentId(), range.from(), range.to(), changedCodes,
                indicatorIds, affected, set.currentTimelineRevisionId(), fingerprint);
    }

    @Transactional
    public ReviewView approve(
            long reviewerId, Collection<String> roles, String requestId,
            String idempotencyKey, ReviewDecisionRequest decision) {
        authorization.requireReviewer(roles);
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        ReviewHead review = repository.findReview(requestId, true)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "审核请求不存在"));
        ParameterVersion version = requireVersion(review.versionId(), true);
        ParameterSetHead set = requireSet(version.parameterSetId(), true);
        authorization.checkBuilding(reviewerId, roles, set.buildingId());
        String decisionHash = sha256(json(objectMapper, decision));
        if ("APPROVED".equals(review.status())) {
            if (normalizedKey.equals(review.decisionIdempotencyKey())
                    && decisionHash.equals(review.decisionRequestSha256())) {
                return reviewView(review, reviewerId, roles);
            }
            throw stateConflict("审核请求已经由其他审批请求处理");
        }
        if (!"PENDING".equals(review.status()) || version.status() != VersionStatus.PENDING_REVIEW) {
            throw stateConflict("审核请求或参数版本已经处理");
        }
        if (Objects.equals(reviewerId, review.submittedBy())
                || Objects.equals(reviewerId, version.ownerUserId())
                || Objects.equals(reviewerId, version.rollbackInitiatorId())) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.REVIEW_CONFLICT,
                    "创建人、提交人或回退发起人不能审批同一版本");
        }
        if (version.submittedRevision() == null
                || version.submittedRevision() != review.submittedRevision()
                || !Objects.equals(version.baseTimelineRevisionId(), set.currentTimelineRevisionId())) {
            throw stateConflict("审核快照或基础时间线已过期");
        }
        EquipmentIdentity equipment = requireEquipment(set.equipmentId());
        validateCompleteSnapshot(equipment, version.values());
        PublishType publishType = PublishType.valueOf(decision.publishType());
        LocalDateTime publishedAt = repository.currentTimestamp();
        TimelineRevision current = repository.findCurrentTimeline(set.parameterSetId(), true).orElse(null);
        Range range;
        Impact impact;
        if (publishType == PublishType.RETROACTIVE) {
            authorization.requireRetroactiveApprover(roles);
            if (decision.retroactiveReason() == null || decision.retroactiveReason().isBlank()
                    || decision.impactFingerprint() == null || decision.impactFingerprint().isBlank()) {
                throw DeviceParameterErrors.error(400,
                        DeviceParameterErrors.RETROACTIVE_APPROVAL_REQUIRED,
                        "追溯审批必须提供原因、证据和未过期影响预览");
            }
            range = validateRetroactiveRange(decision.effectiveFrom(), decision.effectiveTo(),
                    publishedAt, current);
            impact = previewImpactForLockedVersion(version, set, range);
            if (!impact.fingerprint().equals(decision.impactFingerprint())) {
                throw stateConflict("追溯影响预览已过期，请重新预览");
            }
        } else {
            range = new Range(publishedAt, null, publishedAt);
            impact = new Impact(set.equipmentId(), publishedAt, null,
                    changedParameterCodes(version), List.of(), 0,
                    set.currentTimelineRevisionId(), "IMMEDIATE");
        }
        List<TimelineSegment> segments = replaceTimeline(
                current == null ? List.of() : current.segments(),
                range.from(), range.to(), version.versionId());
        RecalculationStatus recalcStatus = impact.indicatorIds().isEmpty()
                ? RecalculationStatus.NOT_REQUIRED : RecalculationStatus.PENDING_RECALC;
        TimelineRevision timeline = new TimelineRevision(id(), set.parameterSetId(),
                repository.nextTimelineRevisionNo(set.parameterSetId()), publishedAt, reviewerId,
                requestId, publishType, publishType == PublishType.RETROACTIVE
                ? decision.retroactiveReason().trim() : null,
                decision.evidenceReference().trim(), recalcStatus, segments);
        String impactJson = json(objectMapper, impact);
        repository.insertTimeline(timeline, impactJson);
        if (repository.markVersionPublished(version.versionId(), publishedAt) != 1
                || repository.updateCurrentTimeline(set.parameterSetId(),
                timeline.timelineRevisionId(), set.configRevision()) != 1
                || repository.approveReview(requestId, reviewerId,
                decision.comment().trim(), publishedAt, normalizedKey, decisionHash) != 1) {
            throw stateConflict("发布期间状态发生变化");
        }
        if (!impact.indicatorIds().isEmpty()) {
            LocalDateTime fromMinute = floorMinute(range.from());
            LocalDateTime toMinute = ceilMinute(range.recalcTo());
            repository.insertRecalculationJob(id(),
                    "DEVICE_PARAMETER|" + timeline.timelineRevisionId(),
                    timeline.timelineRevisionId(), set.buildingId(), set.equipmentId(),
                    json(objectMapper, impact.indicatorIds()), fromMinute, toMinute);
            publishAfterCommit(new DeviceParameterTimelinePublishedEvent(
                    timeline.timelineRevisionId(), set.equipmentId(), impact.indicatorIds(),
                    fromMinute, toMinute));
        }
        legacyCompatibilityService.refreshProjection(equipment, version);
        audit(set.buildingId(), reviewerId, publishType == PublishType.RETROACTIVE
                        ? "PUBLISH_RETROACTIVE" : "PUBLISH_IMMEDIATE",
                "TIMELINE", timeline.timelineRevisionId(), version.versionId(),
                normalizedKey, impact.fingerprint());
        return reviewView(repository.findReview(requestId, false).orElseThrow(), reviewerId, roles);
    }

    @Transactional
    public ReviewView withdraw(
            long userId, Collection<String> roles, String requestId, String reason) {
        authorization.requireMaintainer(roles);
        ReviewHead review = repository.findReview(requestId, true)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "审核请求不存在"));
        ParameterVersion version = requireVersion(review.versionId(), true);
        ParameterSetHead set = requireSet(version.parameterSetId(), false);
        authorization.checkBuilding(userId, roles, set.buildingId());
        if (!Objects.equals(userId, review.submittedBy())) {
            throw DeviceParameterErrors.error(403, DeviceParameterErrors.FORBIDDEN,
                    "只有提交人可以撤回待审核申请");
        }
        LocalDateTime at = now();
        if (repository.withdrawReview(requestId, userId, at) != 1
                || repository.reopenVersionAfterWithdraw(
                version.versionId(), review.submittedRevision()) != 1) {
            throw stateConflict("审核请求已经处理，不能撤回");
        }
        audit(set.buildingId(), userId, "WITHDRAW_REVIEW", "REVIEW", requestId,
                version.versionId(), null, reason.trim());
        return reviewView(repository.findReview(requestId, false).orElseThrow(), userId, roles);
    }

    @Transactional
    public ReviewView reject(
            long reviewerId, Collection<String> roles, String requestId, String reason) {
        authorization.requireReviewer(roles);
        ReviewHead review = repository.findReview(requestId, true)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "审核请求不存在"));
        ParameterVersion version = requireVersion(review.versionId(), true);
        ParameterSetHead set = requireSet(version.parameterSetId(), false);
        authorization.checkBuilding(reviewerId, roles, set.buildingId());
        if (Objects.equals(reviewerId, review.submittedBy())
                || Objects.equals(reviewerId, version.ownerUserId())
                || Objects.equals(reviewerId, version.rollbackInitiatorId())) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.REVIEW_CONFLICT,
                    "创建人或提交人不能审核自己的参数版本");
        }
        LocalDateTime at = now();
        if (repository.rejectReview(requestId, reviewerId, reason.trim(), at) != 1
                || repository.markVersionRejected(version.versionId()) != 1) {
            throw stateConflict("审核请求已经处理");
        }
        audit(set.buildingId(), reviewerId, "REJECT_VERSION", "REVIEW", requestId,
                version.versionId(), null, reason);
        return reviewView(repository.findReview(requestId, false).orElseThrow(), reviewerId, roles);
    }

    @Transactional
    public ParameterVersion createRollbackDraft(
            long userId, Collection<String> roles, String equipmentId, RollbackRequest request) {
        authorization.requireMaintainer(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        ParameterSetHead set = repository.findParameterSet(equipmentId, true)
                .orElseThrow(() -> noEffective("设备尚无参数集"));
        if (repository.findOpenVersion(set.parameterSetId(), false).isPresent()) {
            throw stateConflict("该设备已有未完成参数草稿");
        }
        ParameterVersion source = requireVersion(request.sourceVersionId(), false);
        if (!source.parameterSetId().equals(set.parameterSetId())
                || source.status() != VersionStatus.PUBLISHED) {
            throw invalid("回退来源必须是同一设备的已发布历史版本");
        }
        ParameterVersion current = effectiveVersion(set, now())
                .orElseThrow(() -> noEffective("设备当前没有有效参数版本"));
        ParameterVersion rollback = new ParameterVersion(id(), set.parameterSetId(),
                repository.nextVersionNo(set.parameterSetId()), VersionStatus.DRAFT, 0,
                null, current.versionId(), set.currentTimelineRevisionId(), source.versionId(),
                ChangeType.ROLLBACK, null, null, request.reason().trim(),
                request.evidenceReference().trim(), userId, userId, null,
                copyValues(source.values()));
        repository.insertVersion(rollback);
        repository.insertVersionValues(rollback.versionId(), rollback.values());
        audit(set.buildingId(), userId, "CREATE_ROLLBACK_DRAFT", "VERSION",
                rollback.versionId(), source.versionId(), null, request.reason());
        return repository.findVersion(rollback.versionId(), false).orElseThrow();
    }

    public ParameterVersion effective(
            long userId, Collection<String> roles, String equipmentId,
            LocalDateTime businessAt, LocalDateTime knowledgeAt) {
        authorization.requireReader(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        ParameterSetHead set = repository.findParameterSet(equipmentId, false)
                .orElseThrow(() -> noEffective("设备没有正式参数版本"));
        LocalDateTime business = businessAt == null ? now() : businessAt;
        TimelineRevision timeline = knowledgeAt == null
                ? repository.findCurrentTimeline(set.parameterSetId(), false)
                .orElseThrow(() -> noEffective("设备没有正式参数时间线"))
                : repository.findTimelineAt(set.parameterSetId(), knowledgeAt)
                .orElseThrow(() -> noEffective("指定认知时间没有参数时间线"));
        String versionId = timeline.segments().stream()
                .filter(segment -> contains(segment, business))
                .map(TimelineSegment::versionId).findFirst()
                .orElseThrow(() -> noEffective("指定业务时间没有正式参数版本"));
        return requireVersion(versionId, false);
    }

    @Transactional
    public EffectiveParameterView effectiveView(
            long userId, Collection<String> roles, String equipmentId,
            LocalDateTime businessAt, LocalDateTime knowledgeAt) {
        authorization.requireReader(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        ParameterSetHead set = repository.findParameterSet(equipmentId, false)
                .orElseThrow(() -> noEffective("设备没有正式参数版本"));
        LocalDateTime business = businessAt == null ? now() : businessAt;
        TimelineRevision timeline = knowledgeAt == null
                ? repository.findCurrentTimeline(set.parameterSetId(), false)
                .orElseThrow(() -> noEffective("设备没有正式参数时间线"))
                : repository.findTimelineAt(set.parameterSetId(), knowledgeAt)
                .orElseThrow(() -> noEffective("指定认知时间没有参数时间线"));
        TimelineSegment segment = timeline.segments().stream()
                .filter(item -> contains(item, business)).findFirst()
                .orElseThrow(() -> noEffective("指定业务时间没有正式参数版本"));
        EffectiveParameterView view = new EffectiveParameterView(equipmentId, business, knowledgeAt,
                timeline.timelineRevisionId(), timeline.revisionNo(), timeline.publishedAt(),
                timeline.publishType().name(), timeline.recalculationStatus().name(),
                segment.businessEffectiveFrom(), segment.businessEffectiveTo(),
                requireVersion(segment.versionId(), false));
        audit(equipment.buildingId(), userId, "READ_EFFECTIVE_PARAMETERS", "EQUIPMENT",
                equipmentId, view.version().versionId(), null,
                "timeline=" + timeline.timelineRevisionId());
        return view;
    }

    @Transactional
    public List<ParameterVersion> listVersions(
            long userId, Collection<String> roles, String equipmentId) {
        authorization.requireReader(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        List<ParameterVersion> values = repository.findParameterSet(equipmentId, false)
                .map(set -> repository.listVersions(set.parameterSetId())).orElse(List.of());
        audit(equipment.buildingId(), userId, "READ_PARAMETER_VERSIONS", "EQUIPMENT",
                equipmentId, null, null, "count=" + values.size());
        return values;
    }

    @Transactional
    public List<DifferenceView> diff(
            long userId, Collection<String> roles, String equipmentId,
            String beforeVersionId, String afterVersionId) {
        authorization.requireReader(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        ParameterVersion before = requireVersion(beforeVersionId, false);
        ParameterVersion after = requireVersion(afterVersionId, false);
        ParameterSetHead set = repository.findParameterSet(equipmentId, false)
                .orElseThrow(() -> noEffective("设备没有参数集"));
        if (!before.parameterSetId().equals(set.parameterSetId())
                || !after.parameterSetId().equals(set.parameterSetId())) {
            throw invalid("差异版本必须属于同一设备");
        }
        List<DifferenceView> values = differences(before.values(), after.values());
        audit(equipment.buildingId(), userId, "READ_PARAMETER_DIFF", "EQUIPMENT",
                equipmentId, afterVersionId, null,
                "before=" + beforeVersionId + ",changes=" + values.size());
        return values;
    }

    public List<ReviewView> listReviews(
            long userId, Collection<String> roles, String buildingId) {
        authorization.requireMaintainer(roles);
        authorization.checkBuilding(userId, roles, buildingId);
        return repository.listReviews(buildingId).stream()
                .map(review -> reviewView(review, userId, roles)).toList();
    }

    private List<VersionValue> normalizeDraftValues(
            EquipmentIdentity equipment, List<DraftValueRequest> requests) {
        List<Applicability> applicability = repository.listApplicabilities(
                equipment.equipmentTypeCode(), true);
        Map<String, Applicability> expected = new LinkedHashMap<>();
        applicability.forEach(item -> expected.put(item.definitionId(), item));
        Map<String, DraftValueRequest> supplied = new LinkedHashMap<>();
        for (DraftValueRequest request : requests) {
            if (supplied.putIfAbsent(request.definitionId(), request) != null) {
                throw invalid("完整快照不能包含重复标准参数");
            }
        }
        if (!expected.keySet().equals(supplied.keySet())) {
            throw invalid("草稿必须完整覆盖设备类型当前启用的全部适用参数");
        }
        List<VersionValue> values = new ArrayList<>();
        for (Map.Entry<String, Applicability> entry : expected.entrySet()) {
            Definition definition = requireDefinition(entry.getKey());
            DraftValueRequest request = supplied.get(entry.getKey());
            String valueStatus = request.valueStatus() == null ? "VALUE" : request.valueStatus();
            if ("NOT_CONFIGURED".equals(valueStatus)) {
                if (request.missingReason() == null || request.missingReason().isBlank()) {
                    throw invalid("明确未配置的参数必须填写原因");
                }
                values.add(new VersionValue(definition.definitionId(), definition.parameterCode(),
                        "NOT_CONFIGURED", null, definition.standardUnit(), null, null,
                        null, null, null, request.missingReason().trim(), null));
                continue;
            }
            Candidate candidate = repository.findCandidate(request.candidateId())
                    .orElseThrow(() -> DeviceParameterErrors.error(404,
                            DeviceParameterErrors.NOT_FOUND, "草稿选择的候选不存在"));
            if (candidate.validationStatus() != ValidationStatus.READY
                    || !candidate.equipmentId().equals(equipment.equipmentId())
                    || !candidate.definitionId().equals(definition.definitionId())) {
                throw invalid("草稿只能选择同一设备和参数的 READY 候选");
            }
            ConflictHead conflict = repository.findCurrentConflict(
                    equipment.equipmentId(), definition.definitionId(), false).orElse(null);
            if (conflict != null && (!"RESOLVED".equals(conflict.status())
                    || !candidate.candidateId().equals(conflict.selectedCandidateId()))) {
                throw DeviceParameterErrors.error(409,
                        DeviceParameterErrors.CANDIDATE_CONFLICT,
                        "存在未解决冲突或草稿未使用已选择候选");
            }
            if (candidate.warning()
                    && (request.warningReason() == null || request.warningReason().isBlank())) {
                throw invalid("专业建议范围警告或范围未配置时必须填写审核理由");
            }
            values.add(new VersionValue(definition.definitionId(), definition.parameterCode(),
                    "VALUE", candidate.normalizedValue(), definition.standardUnit(),
                    candidate.candidateId(), candidate.sourceType(), candidate.sourceReference(),
                    candidate.sourceVersion(), candidate.observedAt(), null,
                    request.warningReason() == null ? null : request.warningReason().trim()));
        }
        return List.copyOf(values);
    }

    private void validateCompleteSnapshot(
            EquipmentIdentity equipment, List<VersionValue> values) {
        Map<String, VersionValue> byDefinition = new LinkedHashMap<>();
        for (VersionValue value : values) {
            if (byDefinition.putIfAbsent(value.definitionId(), value) != null) {
                throw invalid("参数版本包含重复标准参数");
            }
        }
        List<Applicability> applicability = repository.listApplicabilities(
                equipment.equipmentTypeCode(), true);
        if (applicability.size() != byDefinition.size()) {
            throw invalid("参数版本未完整覆盖当前启用适用关系");
        }
        for (Applicability item : applicability) {
            VersionValue value = byDefinition.get(item.definitionId());
            Definition definition = requireDefinition(item.definitionId());
            if (value == null || !"ENABLED".equals(definition.status())) {
                throw invalid("参数版本引用的标准定义已变化");
            }
            if (item.required() && !"VALUE".equals(value.valueStatus())) {
                throw invalid("必填标准参数不能明确未配置");
            }
            if ("VALUE".equals(value.valueStatus())) {
                Candidate candidate = repository.findCandidate(value.candidateId())
                        .orElseThrow(() -> invalid("正式值引用的候选不存在"));
                if (candidate.validationStatus() != ValidationStatus.READY
                        || !candidate.equipmentId().equals(equipment.equipmentId())
                        || !candidate.definitionId().equals(item.definitionId())) {
                    throw invalid("正式值引用的候选不再满足发布条件");
                }
                if (outside(value.value(), item.hardMin(), item.hardMax())) {
                    throw DeviceParameterErrors.error(400,
                            DeviceParameterErrors.VALUE_OUT_OF_RANGE,
                            "参数值违反当前专业硬限制: " + definition.parameterCode());
                }
            }
        }
    }

    private List<VersionValue> initialMissingValues(EquipmentIdentity equipment) {
        return repository.listApplicabilities(equipment.equipmentTypeCode(), true).stream()
                .map(item -> {
                    Definition definition = requireDefinition(item.definitionId());
                    return new VersionValue(definition.definitionId(), definition.parameterCode(),
                            "NOT_CONFIGURED", null, definition.standardUnit(), null, null,
                            null, null, null, "INITIAL_DRAFT_NOT_CONFIGURED", null);
                }).toList();
    }

    private Impact previewImpactForLockedVersion(
            ParameterVersion version, ParameterSetHead set, Range range) {
        List<String> changedCodes = changedParameterCodes(version);
        List<String> indicatorIds = formulaImpactPort.affectedIndicatorIds(
                set.equipmentId(), changedCodes, range.from(), range.recalcTo());
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(
                floorMinute(range.from()), ceilMinute(range.recalcTo())));
        long affected = Math.multiplyExact(minutes, indicatorIds.size());
        return new Impact(set.equipmentId(), range.from(), range.to(), changedCodes,
                indicatorIds, affected, set.currentTimelineRevisionId(),
                impactFingerprint(version, set, range, changedCodes, indicatorIds));
    }

    private String impactFingerprint(
            ParameterVersion version, ParameterSetHead set, Range range,
            List<String> changedCodes, List<String> indicatorIds) {
        return sha256(version.versionId() + '|' + version.submittedRevision() + '|'
                + set.currentTimelineRevisionId() + '|' + range.from() + '|' + range.to()
                + '|' + changedCodes + '|' + indicatorIds);
    }

    private List<String> changedParameterCodes(ParameterVersion version) {
        ParameterVersion base = version.baseVersionId() == null ? null
                : repository.findVersion(version.baseVersionId(), false).orElse(null);
        return differences(base == null ? List.of() : base.values(), version.values()).stream()
                .map(DifferenceView::parameterCode).sorted().toList();
    }

    private static List<DifferenceView> differences(
            List<VersionValue> before, List<VersionValue> after) {
        Map<String, VersionValue> left = indexValues(before);
        Map<String, VersionValue> right = indexValues(after);
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(left.keySet());
        ids.addAll(right.keySet());
        List<DifferenceView> result = new ArrayList<>();
        for (String definitionId : ids) {
            VersionValue a = left.get(definitionId);
            VersionValue b = right.get(definitionId);
            if (sameValue(a, b)) {
                continue;
            }
            String type = a == null ? "ADDED" : b == null ? "REMOVED"
                    : !Objects.equals(a.value(), b.value()) ? "VALUE_CHANGED"
                    : !Objects.equals(a.standardUnit(), b.standardUnit()) ? "UNIT_CHANGED"
                    : "SOURCE_CHANGED";
            result.add(new DifferenceView(definitionId,
                    b != null ? b.parameterCode() : a.parameterCode(), type,
                    a == null ? null : a.value(), b == null ? null : b.value(),
                    a == null || a.sourceType() == null ? null : a.sourceType().name(),
                    b == null || b.sourceType() == null ? null : b.sourceType().name()));
        }
        result.sort(Comparator.comparing(DifferenceView::parameterCode));
        return List.copyOf(result);
    }

    private static List<TimelineSegment> replaceTimeline(
            List<TimelineSegment> current, LocalDateTime from,
            LocalDateTime to, String versionId) {
        List<TimelineSegment> result = new ArrayList<>();
        for (TimelineSegment segment : current) {
            boolean before = segment.businessEffectiveTo() != null
                    && !segment.businessEffectiveTo().isAfter(from);
            boolean after = to != null && !segment.businessEffectiveFrom().isBefore(to);
            if (before || after) {
                result.add(segment);
                continue;
            }
            if (segment.businessEffectiveFrom().isBefore(from)) {
                result.add(new TimelineSegment(segment.businessEffectiveFrom(), from,
                        segment.versionId()));
            }
            if (to != null && (segment.businessEffectiveTo() == null
                    || segment.businessEffectiveTo().isAfter(to))) {
                result.add(new TimelineSegment(to, segment.businessEffectiveTo(),
                        segment.versionId()));
            }
        }
        result.add(new TimelineSegment(from, to, versionId));
        result.sort(Comparator.comparing(TimelineSegment::businessEffectiveFrom));
        List<TimelineSegment> merged = new ArrayList<>();
        for (TimelineSegment segment : result) {
            if (segment.businessEffectiveTo() != null
                    && !segment.businessEffectiveFrom().isBefore(segment.businessEffectiveTo())) {
                continue;
            }
            if (!merged.isEmpty()) {
                TimelineSegment previous = merged.getLast();
                if (Objects.equals(previous.versionId(), segment.versionId())
                        && Objects.equals(previous.businessEffectiveTo(),
                        segment.businessEffectiveFrom())) {
                    merged.set(merged.size() - 1, new TimelineSegment(
                            previous.businessEffectiveFrom(), segment.businessEffectiveTo(),
                            previous.versionId()));
                    continue;
                }
                if (previous.businessEffectiveTo() == null
                        || previous.businessEffectiveTo().isAfter(segment.businessEffectiveFrom())) {
                    throw DeviceParameterErrors.error(409,
                            DeviceParameterErrors.TIMELINE_OVERLAP,
                            "生成的参数业务时间线存在重叠");
                }
            }
            merged.add(segment);
        }
        return List.copyOf(merged);
    }

    private Range validateRetroactiveRange(
            LocalDateTime from, LocalDateTime requestedTo, LocalDateTime current,
            TimelineRevision timeline) {
        if (from == null || !from.isBefore(current)) {
            throw DeviceParameterErrors.error(400,
                    DeviceParameterErrors.RETROACTIVE_APPROVAL_REQUIRED,
                    "追溯业务生效时间必须早于发布时间");
        }
        LocalDateTime nextBoundary = timeline == null ? null : timeline.segments().stream()
                .map(TimelineSegment::businessEffectiveFrom)
                .filter(boundary -> boundary.isAfter(from))
                .min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime to = requestedTo == null ? nextBoundary : requestedTo;
        if (to != null && (!to.isAfter(from) || to.isAfter(current))) {
            throw invalid("追溯结束时间必须晚于开始时间且不能晚于发布时间");
        }
        LocalDateTime recalcTo = to == null || to.isAfter(current) ? current : to;
        long minutes = ChronoUnit.MINUTES.between(floorMinute(from), ceilMinute(recalcTo));
        if (minutes <= 0 || minutes > MAX_RECALC_MINUTES) {
            throw invalid("追溯重算范围必须为正且不能超过一年");
        }
        return new Range(from, to, recalcTo);
    }

    private java.util.Optional<ParameterVersion> effectiveVersion(
            ParameterSetHead set, LocalDateTime businessAt) {
        return repository.findCurrentTimeline(set.parameterSetId(), false)
                .flatMap(timeline -> timeline.segments().stream()
                        .filter(segment -> contains(segment, businessAt)).findFirst())
                .flatMap(segment -> repository.findVersion(segment.versionId(), false));
    }

    private ReviewView reviewView(
            ReviewHead review, long userId, Collection<String> roles) {
        List<String> actions = new ArrayList<>();
        if ("PENDING".equals(review.status())
                && authorization.isGlobalAdministrator(roles)
                && review.submittedBy() != userId) {
            actions.add("APPROVE");
            actions.add("REJECT");
        }
        if ("PENDING".equals(review.status()) && review.submittedBy() == userId) {
            actions.add("WITHDRAW");
        }
        return new ReviewView(review.requestId(), review.versionId(), review.requestNo(),
                review.status(), review.submittedRevision(), review.submittedBy(),
                review.submittedAt(), review.reviewerId(), review.reviewComment(),
                review.reviewedAt(), List.copyOf(actions));
    }

    private ParameterVersion requireVersion(String versionId, boolean lock) {
        return repository.findVersion(versionId, lock)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "设备参数版本不存在"));
    }

    private ParameterSetHead requireSet(String parameterSetId, boolean lock) {
        return repository.findParameterSetById(parameterSetId, lock)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "设备参数集不存在"));
    }

    private EquipmentIdentity requireEquipment(String equipmentId) {
        return repository.findEquipment(equipmentId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "设备不存在"));
    }

    private Definition requireDefinition(String definitionId) {
        return repository.findDefinition(definitionId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.DEFINITION_NOT_FOUND, "标准参数定义不存在"));
    }

    private void requireOwner(long userId, ParameterVersion version) {
        if (version.ownerUserId() == null || version.ownerUserId() != userId) {
            throw DeviceParameterErrors.error(403, DeviceParameterErrors.FORBIDDEN,
                    "只有草稿创建人可以编辑或提交该版本");
        }
    }

    private void audit(
            String buildingId, long userId, String action, String objectType,
            String objectId, String versionId, String idempotencyKey, String summary) {
        repository.audit(id(), buildingId, "USER", userId, action, objectType, objectId,
                versionId, null, summary, "SUCCESS", null, idempotencyKey,
                summary == null ? null : sha256(summary));
    }

    private void publishAfterCommit(DeviceParameterTimelinePublishedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventPublisher.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(event);
            }
        });
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw invalid("Idempotency-Key 不能为空且长度不能超过 160");
        }
        return value.trim();
    }

    private static boolean contains(TimelineSegment segment, LocalDateTime businessAt) {
        return !businessAt.isBefore(segment.businessEffectiveFrom())
                && (segment.businessEffectiveTo() == null
                || businessAt.isBefore(segment.businessEffectiveTo()));
    }

    private static List<VersionValue> copyValues(List<VersionValue> values) {
        return values.stream().map(value -> new VersionValue(value.definitionId(),
                value.parameterCode(), value.valueStatus(), value.value(), value.standardUnit(),
                value.candidateId(), value.sourceType(), value.sourceReference(), value.sourceVersion(),
                value.observedAt(), value.missingReason(), value.warningReason())).toList();
    }

    private static Map<String, VersionValue> indexValues(List<VersionValue> values) {
        Map<String, VersionValue> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(value.definitionId(), value));
        return result;
    }

    private static boolean sameValue(VersionValue left, VersionValue right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.valueStatus(), right.valueStatus())
                && decimalEquals(left.value(), right.value())
                && Objects.equals(left.standardUnit(), right.standardUnit())
                && Objects.equals(left.sourceType(), right.sourceType())
                && Objects.equals(left.sourceReference(), right.sourceReference())
                && Objects.equals(left.sourceVersion(), right.sourceVersion());
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean outside(BigDecimal value, BigDecimal min, BigDecimal max) {
        return min != null && value.compareTo(min) < 0 || max != null && value.compareTo(max) > 0;
    }

    private static LocalDateTime floorMinute(LocalDateTime value) {
        return value.truncatedTo(ChronoUnit.MINUTES);
    }

    private static LocalDateTime ceilMinute(LocalDateTime value) {
        LocalDateTime floor = floorMinute(value);
        return floor.equals(value) ? floor : floor.plusMinutes(1);
    }

    private static RuntimeException stateConflict(String message) {
        return DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT, message);
    }

    private static RuntimeException noEffective(String message) {
        return DeviceParameterErrors.error(404,
                DeviceParameterErrors.NO_EFFECTIVE_VERSION, message);
    }

    private record Range(
            LocalDateTime from, LocalDateTime to, LocalDateTime recalcTo) {
    }
}
