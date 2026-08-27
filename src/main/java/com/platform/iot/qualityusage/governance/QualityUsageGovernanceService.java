package com.platform.iot.qualityusage.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.TraceContext;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageRuntimeStateService;
import com.platform.iot.qualityusage.QualityUsageErrors;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.AuditView;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetCreateRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetUpdateRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetView;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.PolicyDraftRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.PolicyVersionView;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.PolicyView;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ReviewRequestView;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ScenarioView;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsageAuditLogMapper;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsageChangeSetMapper;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsageConfigRevisionMapper;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsagePolicyLevelMapper;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsagePolicyMapper;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsagePolicyVersionMapper;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsageReviewRequestMapper;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsageScenarioMapper;
import com.platform.iot.qualityusage.governance.model.QualityUsageGovernanceModels.ChangeSetStatus;
import com.platform.iot.qualityusage.governance.model.QualityUsageGovernanceModels.PolicyVersionStatus;
import com.platform.iot.qualityusage.governance.model.QualityUsageGovernanceModels.ReviewMode;
import com.platform.iot.qualityusage.governance.model.QualityUsageGovernanceModels.ReviewStatus;
import com.platform.iot.qualityusage.governance.model.QualityUsageGovernanceModels.ScenarioStatus;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageAuditLog;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageChangeSet;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageConfigRevision;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsagePolicy;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsagePolicyLevel;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsagePolicyVersion;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageReviewRequest;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageScenario;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * Q0/Q1/Q2 使用策略的 MySQL 治理闭环。
 *
 * <p>这个服务是角色、建筑范围、草稿所有者、审核快照、并发锁和发布事务的唯一边界。
 * 发布先按 {@code pointId + scenarioCode} 的稳定顺序锁定策略身份，再在同一事务内退役、
 * 激活、更新指针、递增一次全局修订号并写审计；任一验证失败都不会留下部分正式版本。
 * 公共审计治理只提供后台职责和研发/生产自审策略，不复制质量策略快照，也不建立第二套审核状态机。</p>
 */
public class QualityUsageGovernanceService {
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PAGE_SIZE = 200;

    private final BizQualityUsageScenarioMapper scenarioMapper;
    private final BizQualityUsagePolicyMapper policyMapper;
    private final BizQualityUsagePolicyVersionMapper versionMapper;
    private final BizQualityUsagePolicyLevelMapper levelMapper;
    private final BizQualityUsageChangeSetMapper changeSetMapper;
    private final BizQualityUsageReviewRequestMapper reviewMapper;
    private final BizQualityUsageAuditLogMapper auditMapper;
    private final BizQualityUsageConfigRevisionMapper configRevisionMapper;
    private final BizDataPointMapper pointMapper;
    private final BuildingScopeService buildingScopeService;
    private final BackendDutyService dutyService;
    private final AuditGovernanceProperties auditProperties;
    private final QualityUsageRuntimeStateService runtimeStateService;
    private final ObjectMapper objectMapper;

    public List<ScenarioView> listScenarios(Long userId, Collection<String> roles) {
        requireReader(roles);
        return scenarioMapper.selectList(new LambdaQueryWrapper<BizQualityUsageScenario>())
                .stream()
                .sorted(Comparator.comparing(BizQualityUsageScenario::getScenarioCode))
                .map(this::scenarioView)
                .toList();
    }

    public PageResponse<PolicyView> listActivePolicies(
            Long userId, Collection<String> roles, String buildingId, int page, int size) {
        requireReader(roles);
        validatePage(page, size);
        if (StringUtils.hasText(buildingId)) {
            checkBuildingAccess(userId, roles, buildingId);
        }
        Set<String> scope = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        Map<String, BizQualityUsageScenario> scenarios = scenarioById();
        List<PolicyView> visible = policyMapper.selectList(new LambdaQueryWrapper<BizQualityUsagePolicy>())
                .stream()
                .filter(policy -> buildingId == null || buildingId.equals(policy.getBuildingId()))
                .filter(policy -> scope == null || scope.contains(policy.getBuildingId()))
                .filter(policy -> policy.getCurrentActiveVersionId() != null)
                .sorted(policyOrder(scenarios))
                .map(policy -> activePolicyView(policy, scenarios.get(policy.getScenarioId())))
                .toList();
        return page(visible, page, size);
    }

    public PolicyView policyDetail(Long userId, Collection<String> roles, String policyId) {
        requireReader(roles);
        BizQualityUsagePolicy policy = requirePolicy(policyId);
        checkBuildingAccess(userId, roles, policy.getBuildingId());
        if (policy.getCurrentActiveVersionId() == null && !isAdmin(roles)) {
            notVisible();
        }
        return activePolicyView(policy, requireScenarioById(policy.getScenarioId()));
    }

    public List<PolicyVersionView> listVersions(Long userId, Collection<String> roles, String policyId) {
        requireReader(roles);
        BizQualityUsagePolicy policy = requirePolicy(policyId);
        checkBuildingAccess(userId, roles, policy.getBuildingId());
        List<BizQualityUsagePolicyVersion> versions = versionMapper.selectByPolicyId(policyId);
        return versions.stream()
                .filter(version -> versionVisible(version, policy, userId, roles))
                .map(this::versionView)
                .toList();
    }

    public PageResponse<ChangeSetView> listChangeSets(
            Long userId, Collection<String> roles, String buildingId, int page, int size) {
        requireMaintainer(roles);
        validatePage(page, size);
        if (StringUtils.hasText(buildingId)) {
            checkBuildingAccess(userId, roles, buildingId);
        }
        Set<String> scope = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        List<ChangeSetView> visible = changeSetMapper.selectList(new LambdaQueryWrapper<BizQualityUsageChangeSet>())
                .stream()
                .filter(changeSet -> buildingId == null || buildingId.equals(changeSet.getBuildingId()))
                .filter(changeSet -> scope == null || scope.contains(changeSet.getBuildingId()))
                .filter(changeSet -> changeSetVisible(changeSet, userId, roles))
                .sorted(Comparator.comparing(BizQualityUsageChangeSet::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::changeSetView)
                .toList();
        return page(visible, page, size);
    }

    public ChangeSetView changeSetDetail(Long userId, Collection<String> roles, String changeSetId) {
        requireMaintainer(roles);
        BizQualityUsageChangeSet changeSet = requireChangeSet(changeSetId);
        checkChangeSetVisible(changeSet, userId, roles);
        return changeSetView(changeSet);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChangeSetView createChangeSet(
            Long userId, Collection<String> roles, ChangeSetCreateRequest request) {
        requireMaintainer(roles);
        String buildingId = requireText(request.buildingId(), "建筑不能为空");
        checkBuildingAccess(userId, roles, buildingId);
        LocalDateTime now = now();
        BizQualityUsageChangeSet changeSet = new BizQualityUsageChangeSet();
        changeSet.setChangeSetId(id());
        changeSet.setBuildingId(buildingId);
        changeSet.setStatus(ChangeSetStatus.DRAFT.name());
        changeSet.setRevision(0);
        changeSet.setCreatedBy(userId);
        changeSet.setHasBeenSubmitted(0);
        changeSet.setTitle(requireText(request.title(), "变更集标题不能为空"));
        changeSet.setDescription(trimToNull(request.description()));
        changeSet.setCreateTime(now);
        changeSet.setUpdateTime(now);
        changeSetMapper.insert(changeSet);
        replaceDrafts(changeSet, request.policies() == null ? List.of() : request.policies());
        audit(userId, buildingId, "CREATE_CHANGE_SET", "CHANGE_SET", changeSet.getChangeSetId(), null,
                null, "status=DRAFT", "SUCCESS", null, null, null, null);
        return changeSetView(changeSet);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChangeSetView updateChangeSet(
            Long userId, Collection<String> roles, String changeSetId, ChangeSetUpdateRequest request) {
        requireMaintainer(roles);
        BizQualityUsageChangeSet changeSet = requireLockedChangeSet(changeSetId);
        requireChangeSetEditor(changeSet, userId, roles);
        requireState(changeSet.getStatus(), ChangeSetStatus.DRAFT.name(), "变更集当前不可编辑");
        if (!Objects.equals(changeSet.getRevision(), request.expectedRevision())) {
            throw versionConflict();
        }
        String before = changeSetSummary(changeSet);
        LocalDateTime now = now();
        int updated = changeSetMapper.updateDraft(changeSetId,
                requireText(request.title(), "变更集标题不能为空"), trimToNull(request.description()),
                request.expectedRevision(), now);
        if (updated != 1) {
            throw versionConflict();
        }
        replaceDrafts(changeSet, request.policies());
        BizQualityUsageChangeSet result = requireChangeSet(changeSetId);
        audit(userId, result.getBuildingId(), "UPDATE_CHANGE_SET", "CHANGE_SET", changeSetId, null,
                before, changeSetSummary(result), "SUCCESS", null, null, null, null);
        return changeSetView(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteChangeSet(Long userId, Collection<String> roles, String changeSetId) {
        requireMaintainer(roles);
        BizQualityUsageChangeSet changeSet = requireLockedChangeSet(changeSetId);
        requireChangeSetEditor(changeSet, userId, roles);
        requireState(changeSet.getStatus(), ChangeSetStatus.DRAFT.name(), "变更集当前不可删除");
        if (Boolean.TRUE.equals(flag(changeSet.getHasBeenSubmitted()))) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT,
                    "已经提交过的变更集只能取消，不能物理删除");
        }
        deleteNeverSubmittedDrafts(changeSetId);
        audit(userId, changeSet.getBuildingId(), "DELETE_CHANGE_SET", "CHANGE_SET", changeSetId, null,
                changeSetSummary(changeSet), null, "SUCCESS", null, null, null, null);
        changeSetMapper.deleteById(changeSetId);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ReviewRequestView submit(
            Long userId, Collection<String> roles, String changeSetId, String idempotencyKey, String comment) {
        requireMaintainer(roles);
        dutyService.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = digest("SUBMIT|" + changeSetId + "|" + safe(comment));
        BizQualityUsageChangeSet changeSet = requireLockedChangeSet(changeSetId);
        requireChangeSetEditor(changeSet, userId, roles);
        BizQualityUsageAuditLog lockedReplay = replay(key, requestHash);
        if (lockedReplay != null) {
            return reviewView(requireReview(lockedReplay.getVersionId()),
                    requireChangeSetByReview(lockedReplay.getVersionId()));
        }
        requireState(changeSet.getStatus(), ChangeSetStatus.DRAFT.name(), "变更集当前不可提交");
        List<PolicyEnvelope> drafts = lockDraftsInStableOrder(changeSet);
        if (drafts.isEmpty()) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED, "变更集至少需要一条策略");
        }
        for (PolicyEnvelope draft : drafts) {
            if (draft.policy().getPendingReviewRequestId() != null) {
                throw QualityUsageErrors.error(409, QualityUsageErrors.PENDING_CONFLICT,
                        "策略身份已有待审核变更");
            }
        }
        Snapshot snapshot = snapshot(drafts);
        LocalDateTime now = now();
        BizQualityUsageReviewRequest review = newReview(changeSet, userId, ReviewMode.NORMAL,
                snapshot, key, requestHash, comment, now);
        reviewMapper.insert(review);
        for (PolicyEnvelope draft : drafts) {
            policyMapper.updatePointers(draft.policy().getPolicyId(), draft.policy().getCurrentActiveVersionId(),
                    review.getRequestId(), now);
        }
        changeSetMapper.updateLifecycle(changeSetId, ChangeSetStatus.PENDING.name(), changeSet.getRevision(), 1,
                now, null, null, null, now);
        audit(userId, changeSet.getBuildingId(), "SUBMIT", "CHANGE_SET", changeSetId, review.getRequestId(),
                changeSetSummary(changeSet), "status=PENDING;sha256=" + snapshot.sha256(), "SUCCESS", null,
                null, key, requestHash);
        return reviewView(review, changeSet);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ChangeSetView withdraw(
            Long userId, Collection<String> roles, String changeSetId, String idempotencyKey, String reason) {
        requireMaintainer(roles);
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = digest("WITHDRAW|" + changeSetId + "|" + safe(reason));
        BizQualityUsageChangeSet changeSet = requireLockedChangeSet(changeSetId);
        requireChangeSetEditor(changeSet, userId, roles);
        if (replay(key, requestHash) != null) {
            return changeSetView(requireChangeSet(changeSetId));
        }
        requireState(changeSet.getStatus(), ChangeSetStatus.PENDING.name(), "变更集当前不可撤回");
        BizQualityUsageReviewRequest review = requirePendingReview(changeSet);
        LocalDateTime now = now();
        review.setStatus(ReviewStatus.WITHDRAWN.name());
        review.setWithdrawnBy(userId);
        review.setWithdrawnAt(now);
        review.setReviewComment(trimToNull(reason));
        review.setUpdateTime(now);
        reviewMapper.updateById(review);
        policyMapper.clearPendingByRequest(review.getRequestId(), now);
        changeSetMapper.updateLifecycle(changeSetId, ChangeSetStatus.DRAFT.name(), null, 1,
                null, null, null, "WITHDRAWN", now);
        audit(userId, changeSet.getBuildingId(), "WITHDRAW", "CHANGE_SET", changeSetId, review.getRequestId(),
                "status=PENDING", "status=DRAFT", "SUCCESS", "WITHDRAWN", null, key, requestHash);
        return changeSetView(requireChangeSet(changeSetId));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ChangeSetView cancel(
            Long userId, Collection<String> roles, String changeSetId, String idempotencyKey, String reason) {
        requireMaintainer(roles);
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = digest("CANCEL|" + changeSetId + "|" + safe(reason));
        BizQualityUsageChangeSet changeSet = requireLockedChangeSet(changeSetId);
        requireChangeSetEditor(changeSet, userId, roles);
        if (replay(key, requestHash) != null) {
            return changeSetView(requireChangeSet(changeSetId));
        }
        requireState(changeSet.getStatus(), ChangeSetStatus.DRAFT.name(), "变更集当前不可取消");
        if (!Boolean.TRUE.equals(flag(changeSet.getHasBeenSubmitted()))) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT,
                    "从未提交的草稿应物理删除，不可取消");
        }
        LocalDateTime now = now();
        changeSetMapper.updateLifecycle(changeSetId, ChangeSetStatus.CANCELLED.name(), changeSet.getSubmittedRevision(), 1,
                changeSet.getSubmittedAt(), null, now, "CANCELLED", now);
        audit(userId, changeSet.getBuildingId(), "CANCEL", "CHANGE_SET", changeSetId, null,
                changeSetSummary(changeSet), "status=CANCELLED", "SUCCESS", "CANCELLED", null, key, requestHash);
        return changeSetView(requireChangeSet(changeSetId));
    }

    /** 兼容保留旧发布入口，但始终拒绝绕过质量策略自己的审核状态机。 */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public ReviewRequestView directPublish(
            Long userId, Collection<String> roles, String changeSetId, String idempotencyKey, String reason) {
        throw AuditGovernanceErrors.reviewRequired();
    }

    @Transactional(noRollbackFor = BusinessException.class, isolation = Isolation.READ_COMMITTED)
    public ReviewRequestView approve(
            Long userId, Collection<String> roles, String requestId, String idempotencyKey, String comment) {
        requireAdmin(roles);
        dutyService.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        String reviewComment = requireText(comment, "审核意见不能为空");
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = digest("APPROVE|" + requestId + "|" + safe(comment));
        BizQualityUsageReviewRequest review = requireLockedReview(requestId);
        BizQualityUsageChangeSet changeSet = requireLockedChangeSet(review.getChangeSetId());
        checkBuildingAccess(userId, roles, changeSet.getBuildingId());
        BizQualityUsageAuditLog lockedReplay = replay(key, requestHash);
        if (lockedReplay != null) {
            return reviewView(requireReview(requestId), requireChangeSetByReview(requestId));
        }
        requireState(review.getStatus(), ReviewStatus.PENDING.name(), "审核申请当前不可批准");
        requireState(changeSet.getStatus(), ChangeSetStatus.PENDING.name(), "变更集当前不可批准");
        dutyService.requireSeparation(review.getSubmittedBy(), userId);
        boolean selfApproval = Objects.equals(review.getSubmittedBy(), userId);
        review.setReviewComment(reviewComment);
        Object publishSavepoint = createPublishSavepoint();
        try {
            publish(changeSet, review, userId, key, requestHash,
                    selfApproval ? "SELF_APPROVAL_DEV_MODE" : "APPROVE");
            releasePublishSavepoint(publishSavepoint);
            return reviewView(requireReview(requestId), requireChangeSet(review.getChangeSetId()));
        } catch (BusinessException exception) {
            rollbackPublishSavepoint(publishSavepoint);
            rejectAfterPublicationConflict(changeSet, review, userId, key, requestHash, exception.getErrorCode());
            throw exception;
        }
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ReviewRequestView reject(
            Long userId, Collection<String> roles, String requestId, String idempotencyKey, String reason) {
        requireAdmin(roles);
        dutyService.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = digest("REJECT|" + requestId + "|" + safe(reason));
        BizQualityUsageReviewRequest review = requireLockedReview(requestId);
        BizQualityUsageChangeSet changeSet = requireLockedChangeSet(review.getChangeSetId());
        checkBuildingAccess(userId, roles, changeSet.getBuildingId());
        if (replay(key, requestHash) != null) {
            return reviewView(requireReview(requestId), requireChangeSetByReview(requestId));
        }
        requireState(review.getStatus(), ReviewStatus.PENDING.name(), "审核申请当前不可拒绝");
        requireState(changeSet.getStatus(), ChangeSetStatus.PENDING.name(), "变更集当前不可拒绝");
        dutyService.requireSeparation(review.getSubmittedBy(), userId);
        LocalDateTime now = now();
        review.setStatus(ReviewStatus.REJECTED.name());
        review.setReviewerId(userId);
        review.setReviewComment(requireText(reason, "拒绝原因不能为空"));
        review.setReviewedAt(now);
        review.setUpdateTime(now);
        reviewMapper.updateById(review);
        policyMapper.clearPendingByRequest(requestId, now);
        changeSetMapper.updateLifecycle(changeSet.getChangeSetId(), ChangeSetStatus.DRAFT.name(), null, 1,
                null, null, null, "REJECTED", now);
        audit(userId, changeSet.getBuildingId(), "REJECT", "REVIEW_REQUEST", requestId, requestId,
                "status=PENDING", "status=REJECTED", "SUCCESS", "REJECTED", null, key, requestHash);
        return reviewView(review, changeSet);
    }

    public PageResponse<ReviewRequestView> listReviews(
            Long userId, Collection<String> roles, String buildingId, int page, int size) {
        requireMaintainer(roles);
        validatePage(page, size);
        if (StringUtils.hasText(buildingId)) {
            checkBuildingAccess(userId, roles, buildingId);
        }
        Set<String> scope = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        Map<String, BizQualityUsageChangeSet> changeSets = changeSetMapper.selectList(
                        new LambdaQueryWrapper<BizQualityUsageChangeSet>())
                .stream().collect(Collectors.toMap(BizQualityUsageChangeSet::getChangeSetId, value -> value));
        List<ReviewRequestView> visible = reviewMapper.selectList(new LambdaQueryWrapper<BizQualityUsageReviewRequest>())
                .stream()
                .map(review -> new ReviewAndChangeSet(review, changeSets.get(review.getChangeSetId())))
                .filter(pair -> pair.changeSet() != null)
                .filter(pair -> buildingId == null || buildingId.equals(pair.changeSet().getBuildingId()))
                .filter(pair -> scope == null || scope.contains(pair.changeSet().getBuildingId()))
                .filter(pair -> reviewVisible(pair.review(), pair.changeSet(), userId, roles))
                .sorted(Comparator.comparing((ReviewAndChangeSet value) -> value.review().getSubmittedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(pair -> reviewView(pair.review(), pair.changeSet()))
                .toList();
        return page(visible, page, size);
    }

    public ReviewRequestView reviewDetail(Long userId, Collection<String> roles, String requestId) {
        requireMaintainer(roles);
        BizQualityUsageReviewRequest review = requireReview(requestId);
        BizQualityUsageChangeSet changeSet = requireChangeSet(review.getChangeSetId());
        if (!reviewVisible(review, changeSet, userId, roles)) {
            notVisible();
        }
        checkBuildingAccess(userId, roles, changeSet.getBuildingId());
        return reviewView(review, changeSet);
    }

    public PageResponse<AuditView> listAudits(
            Long userId, Collection<String> roles, String buildingId, int page, int size) {
        requireMaintainer(roles);
        validatePage(page, size);
        if (StringUtils.hasText(buildingId)) {
            checkBuildingAccess(userId, roles, buildingId);
        }
        Set<String> scope = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        List<AuditView> visible = auditMapper.selectList(new LambdaQueryWrapper<BizQualityUsageAuditLog>())
                .stream()
                .filter(audit -> buildingId == null || buildingId.equals(audit.getBuildingId()))
                .filter(audit -> scope == null || scope.contains(audit.getBuildingId()))
                .filter(audit -> isAdmin(roles) || Objects.equals(audit.getOperatorId(), userId))
                .sorted(Comparator.comparing(BizQualityUsageAuditLog::getOperationTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::auditView)
                .toList();
        return page(visible, page, size);
    }

    private void publish(BizQualityUsageChangeSet changeSet, BizQualityUsageReviewRequest review,
                         Long operatorId, String idempotencyKey, String requestHash, String action) {
        List<PolicyEnvelope> drafts = lockDraftsInStableOrder(changeSet);
        if (drafts.isEmpty()) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED, "变更集至少需要一条策略");
        }
        Snapshot currentSnapshot = snapshot(drafts);
        if (!Objects.equals(review.getSnapshotSha256(), currentSnapshot.sha256())) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.VERSION_CONFLICT, "审核快照已经变化");
        }
        for (PolicyEnvelope draft : drafts) {
            validatePublishable(changeSet, review, draft);
        }

        BizQualityUsageConfigRevision config = configRevisionMapper.selectForUpdate();
        if (config == null || config.getConfigRevision() == null) {
            throw QualityUsageErrors.error(503, QualityUsageErrors.SNAPSHOT_UNAVAILABLE, "质量使用配置修订号不可用");
        }
        long nextRevision = config.getConfigRevision() + 1;
        long effectiveFromMs = nextCompleteMinute(databaseNowMs());
        LocalDateTime now = now();
        for (PolicyEnvelope draft : drafts) {
            String oldActiveId = draft.policy().getCurrentActiveVersionId();
            if (oldActiveId != null) {
                BizQualityUsagePolicyVersion oldActive = versionMapper.selectByIdForUpdate(oldActiveId);
                if (oldActive == null || !PolicyVersionStatus.ACTIVE.name().equals(oldActive.getStatus())) {
                    throw QualityUsageErrors.error(409, QualityUsageErrors.VERSION_CONFLICT, "当前正式策略版本已经变化");
                }
                if (versionMapper.retire(oldActiveId, effectiveFromMs, now) != 1) {
                    throw QualityUsageErrors.error(409, QualityUsageErrors.VERSION_CONFLICT, "当前正式策略版本已经变化");
                }
            }
            if (versionMapper.activate(draft.version().getVersionId(), effectiveFromMs, nextRevision,
                    operatorId, now) != 1) {
                throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "策略草稿当前不可发布");
            }
            policyMapper.updatePointers(draft.policy().getPolicyId(), draft.version().getVersionId(), null, now);
        }
        configRevisionMapper.updateRevision(nextRevision,
                bounded("action=" + action + ";changeSet=" + changeSet.getChangeSetId()
                        + ";items=" + drafts.size(), 500), now);

        review.setStatus(ReviewStatus.APPROVED.name());
        review.setReviewerId(operatorId);
        review.setReviewedAt(now);
        review.setUpdateTime(now);
        reviewMapper.updateById(review);
        changeSetMapper.updateLifecycle(changeSet.getChangeSetId(), ChangeSetStatus.PUBLISHED.name(),
                changeSet.getSubmittedRevision(), 1, changeSet.getSubmittedAt(), now, null, null, now);
        audit(operatorId, changeSet.getBuildingId(), action, "CHANGE_SET", changeSet.getChangeSetId(),
                review.getRequestId(), "status=PENDING", "status=PUBLISHED;revision=" + nextRevision,
                "SUCCESS", null, nextRevision, idempotencyKey, requestHash);
        runtimeStateService.refreshAfterCommit();
    }

    /**
     * 发布校验失败时审核记录必须落为 REJECTED，但此前已处理的版本不得部分生效。
     * 保存点将可撤销的退役、激活和修订递增与随后应持久化的拒绝结果分开。
     */
    private Object createPublishSavepoint() {
        return TransactionAspectSupport.currentTransactionStatus().createSavepoint();
    }

    private void rollbackPublishSavepoint(Object savepoint) {
        TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
    }

    private void releasePublishSavepoint(Object savepoint) {
        TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);
    }

    private void validatePublishable(BizQualityUsageChangeSet changeSet,
                                     BizQualityUsageReviewRequest review,
                                     PolicyEnvelope draft) {
        if (!PolicyVersionStatus.DRAFT.name().equals(draft.version().getStatus())) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "变更集包含不可发布版本");
        }
        if (!Objects.equals(draft.version().getChangeSetId(), changeSet.getChangeSetId())) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "策略草稿不属于当前变更集");
        }
        if (!Objects.equals(draft.version().getBaseActiveVersionId(), draft.policy().getCurrentActiveVersionId())) {
            throw versionConflict();
        }
        if (!Objects.equals(draft.policy().getPendingReviewRequestId(), review.getRequestId())) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.PENDING_CONFLICT, "待审核策略身份已经变化");
        }
        BizDataPoint point = pointMapper.selectByIdForUpdate(draft.policy().getPointId());
        if (point == null || !Objects.equals(point.getBuildingId(), changeSet.getBuildingId())
                || !"ONLINE".equals(point.getStatus())) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.POINT_NOT_ELIGIBLE, "测点当前不可发布策略");
        }
        BizQualityUsageScenario scenario = scenarioMapper.selectByCodeForUpdate(draft.scenario().getScenarioCode());
        if (scenario == null || !ScenarioStatus.ENABLED.name().equals(scenario.getStatus())) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.SCENARIO_DISABLED, "质量使用场景当前已停用");
        }
        validateAllowedQualities(draft.levels());
    }

    private void rejectAfterPublicationConflict(BizQualityUsageChangeSet changeSet,
                                                BizQualityUsageReviewRequest review,
                                                Long operatorId,
                                                String idempotencyKey,
                                                String requestHash,
                                                String errorCode) {
        LocalDateTime now = now();
        review.setStatus(ReviewStatus.REJECTED.name());
        review.setReviewerId(operatorId);
        review.setReviewComment("发布前校验未通过");
        review.setReviewedAt(now);
        review.setUpdateTime(now);
        reviewMapper.updateById(review);
        policyMapper.clearPendingByRequest(review.getRequestId(), now);
        changeSetMapper.updateLifecycle(changeSet.getChangeSetId(), ChangeSetStatus.DRAFT.name(), null, 1,
                null, null, null, errorCode == null ? QualityUsageErrors.STATE_CONFLICT : errorCode, now);
        audit(operatorId, changeSet.getBuildingId(), "PUBLISH_CONFLICT", "CHANGE_SET",
                changeSet.getChangeSetId(), review.getRequestId(), "status=PENDING", "status=DRAFT",
                "REJECTED", errorCode, null, idempotencyKey, requestHash);
    }

    private void replaceDrafts(BizQualityUsageChangeSet changeSet, List<PolicyDraftRequest> requests) {
        List<NormalizedDraft> normalized = normalizeDrafts(requests);
        List<BizQualityUsagePolicyVersion> existing = versionMapper.selectByChangeSetId(changeSet.getChangeSetId())
                .stream()
                .filter(version -> PolicyVersionStatus.DRAFT.name().equals(version.getStatus()))
                .toList();
        Map<String, BizQualityUsagePolicyVersion> existingByPolicy = existing.stream()
                .collect(Collectors.toMap(BizQualityUsagePolicyVersion::getPolicyId, value -> value,
                        (left, right) -> {
                            throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT,
                                    "同一变更集出现重复策略草稿");
                        }, LinkedHashMap::new));
        Map<String, NormalizedDraft> inputByIdentity = normalized.stream()
                .collect(Collectors.toMap(NormalizedDraft::identity, value -> value));

        if (Boolean.TRUE.equals(flag(changeSet.getHasBeenSubmitted()))) {
            for (BizQualityUsagePolicyVersion version : existing) {
                BizQualityUsagePolicy policy = requirePolicy(version.getPolicyId());
                BizQualityUsageScenario scenario = requireScenarioById(policy.getScenarioId());
                String identity = policy.getPointId() + "|" + scenario.getScenarioCode();
                if (!inputByIdentity.containsKey(identity)) {
                    throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED,
                            "已提交的变更集不能删除已有策略身份");
                }
            }
        } else {
            for (BizQualityUsagePolicyVersion version : existing) {
                BizQualityUsagePolicy policy = requirePolicy(version.getPolicyId());
                BizQualityUsageScenario scenario = requireScenarioById(policy.getScenarioId());
                if (!inputByIdentity.containsKey(policy.getPointId() + "|" + scenario.getScenarioCode())) {
                    levelMapper.deleteByVersionId(version.getVersionId());
                    versionMapper.deleteById(version.getVersionId());
                }
            }
        }

        for (NormalizedDraft draft : normalized) {
            BizQualityUsageScenario scenario = requireScenario(draft.scenarioCode());
            BizDataPoint point = requireEligiblePoint(draft.pointId(), changeSet.getBuildingId());
            BizQualityUsagePolicy policy = policyMapper.selectByPointAndScenarioForUpdate(point.getPointId(),
                    scenario.getScenarioId());
            if (policy == null) {
                policy = new BizQualityUsagePolicy();
                policy.setPolicyId(id());
                policy.setBuildingId(changeSet.getBuildingId());
                policy.setPointId(point.getPointId());
                policy.setScenarioId(scenario.getScenarioId());
                policy.setCreateTime(now());
                policy.setUpdateTime(policy.getCreateTime());
                try {
                    policyMapper.insert(policy);
                } catch (DataIntegrityViolationException exception) {
                    throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "策略身份并发创建冲突");
                }
                policy = policyMapper.selectByIdForUpdate(policy.getPolicyId());
            }
            if (!Objects.equals(policy.getBuildingId(), changeSet.getBuildingId())) {
                throw QualityUsageErrors.error(409, QualityUsageErrors.POINT_NOT_ELIGIBLE, "测点归属与变更集建筑不一致");
            }
            BizQualityUsagePolicyVersion version = existingByPolicy.get(policy.getPolicyId());
            Set<QualityLevel> allowed = draft.allowedQualities();
            String copiedFrom = draft.copiedFromVersionId();
            if (copiedFrom != null) {
                BizQualityUsagePolicyVersion source = requireVersion(copiedFrom);
                if (!Objects.equals(source.getPolicyId(), policy.getPolicyId())
                        || PolicyVersionStatus.DRAFT.name().equals(source.getStatus())) {
                    throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED,
                            "回滚复制来源必须是同一策略的正式历史版本");
                }
                allowed = allowedQualities(source.getVersionId());
            }
            validateAllowedQualities(allowed);
            if (version == null) {
                version = new BizQualityUsagePolicyVersion();
                version.setVersionId(id());
                version.setPolicyId(policy.getPolicyId());
                version.setChangeSetId(changeSet.getChangeSetId());
                version.setVersionNo(versionMapper.selectMaxVersionNo(policy.getPolicyId()) + 1);
                version.setStatus(PolicyVersionStatus.DRAFT.name());
                version.setBaseActiveVersionId(policy.getCurrentActiveVersionId());
                version.setCopiedFromVersionId(copiedFrom);
                version.setInitialBaseline(0);
                version.setChangeSource(copiedFrom == null ? "MANUAL" : "ROLLBACK_COPY");
                version.setChangeReason(draft.changeReason());
                version.setCreatedBy(changeSet.getCreatedBy());
                version.setCreateTime(now());
                version.setUpdateTime(version.getCreateTime());
                versionMapper.insert(version);
            } else {
                versionMapper.updateDraftMetadata(version.getVersionId(), copiedFrom,
                        copiedFrom == null ? "MANUAL" : "ROLLBACK_COPY", draft.changeReason(), now());
            }
            setAllowedQualities(version.getVersionId(), allowed);
        }
    }

    private List<NormalizedDraft> normalizeDrafts(List<PolicyDraftRequest> requests) {
        if (requests == null) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED, "策略草稿不能为空");
        }
        Map<String, NormalizedDraft> byIdentity = new LinkedHashMap<>();
        for (PolicyDraftRequest request : requests) {
            if (request == null) {
                throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED, "策略草稿不能为空");
            }
            String pointId = requireText(request.pointId(), "测点不能为空");
            String scenarioCode = requireText(request.scenarioCode(), "场景不能为空");
            Set<QualityLevel> levels = parseLevels(request.allowedQualities());
            String identity = pointId + "|" + scenarioCode;
            NormalizedDraft current = new NormalizedDraft(pointId, scenarioCode, levels,
                    trimToNull(request.copiedFromVersionId()), requireText(request.changeReason(), "变更原因不能为空"));
            if (byIdentity.putIfAbsent(identity, current) != null) {
                throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED,
                        "同一变更集不能重复配置同一测点和场景");
            }
        }
        return byIdentity.values().stream().sorted(Comparator.comparing(NormalizedDraft::pointId)
                .thenComparing(NormalizedDraft::scenarioCode)).toList();
    }

    private List<PolicyEnvelope> lockDraftsInStableOrder(BizQualityUsageChangeSet changeSet) {
        List<BizQualityUsagePolicyVersion> versions = versionMapper.selectByChangeSetId(changeSet.getChangeSetId());
        List<PolicyEnvelope> candidates = new ArrayList<>();
        for (BizQualityUsagePolicyVersion version : versions) {
            if (!PolicyVersionStatus.DRAFT.name().equals(version.getStatus())) {
                continue;
            }
            BizQualityUsagePolicy policy = requirePolicy(version.getPolicyId());
            BizQualityUsageScenario scenario = requireScenarioById(policy.getScenarioId());
            candidates.add(new PolicyEnvelope(policy, scenario, version, allowedQualities(version.getVersionId())));
        }
        candidates.sort(Comparator.comparing((PolicyEnvelope value) -> value.policy().getPointId())
                .thenComparing(value -> value.scenario().getScenarioCode()));
        List<PolicyEnvelope> locked = new ArrayList<>();
        for (PolicyEnvelope candidate : candidates) {
            BizQualityUsagePolicy policy = policyMapper.selectByIdForUpdate(candidate.policy().getPolicyId());
            if (policy == null) {
                throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "策略身份已不存在");
            }
            BizQualityUsagePolicyVersion version = versionMapper.selectByIdForUpdate(candidate.version().getVersionId());
            if (version == null) {
                throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "策略草稿已不存在");
            }
            locked.add(new PolicyEnvelope(policy, candidate.scenario(), version,
                    allowedQualities(version.getVersionId())));
        }
        return locked;
    }

    private Snapshot snapshot(List<PolicyEnvelope> drafts) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PolicyEnvelope draft : drafts.stream()
                .sorted(Comparator.comparing((PolicyEnvelope value) -> value.policy().getPointId())
                        .thenComparing(value -> value.scenario().getScenarioCode())).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("policyId", draft.policy().getPolicyId());
            item.put("pointId", draft.policy().getPointId());
            item.put("scenarioCode", draft.scenario().getScenarioCode());
            item.put("versionId", draft.version().getVersionId());
            item.put("versionNo", draft.version().getVersionNo());
            item.put("baseActiveVersionId", draft.version().getBaseActiveVersionId());
            item.put("allowedQualities", qualityNames(draft.levels()));
            items.add(item);
        }
        try {
            String json = objectMapper.writeValueAsString(items);
            return new Snapshot(json, digest(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成质量策略审核快照", exception);
        }
    }

    private BizQualityUsageReviewRequest newReview(BizQualityUsageChangeSet changeSet, Long userId,
                                                    ReviewMode mode, Snapshot snapshot, String idempotencyKey,
                                                    String requestSha256, String comment, LocalDateTime now) {
        BizQualityUsageReviewRequest review = new BizQualityUsageReviewRequest();
        review.setRequestId(id());
        review.setChangeSetId(changeSet.getChangeSetId());
        review.setRequestNo(reviewMapper.selectMaxRequestNo(changeSet.getChangeSetId()) + 1);
        review.setStatus(ReviewStatus.PENDING.name());
        review.setReviewMode(mode.name());
        review.setSubmittedRevision(changeSet.getRevision());
        review.setSnapshotJson(snapshot.json());
        review.setSnapshotSha256(snapshot.sha256());
        review.setSubmittedBy(userId);
        review.setSubmittedAt(now);
        review.setReviewComment(trimToNull(comment));
        review.setIdempotencyKey(idempotencyKey);
        review.setRequestSha256(requestSha256);
        review.setCreateTime(now);
        review.setUpdateTime(now);
        return review;
    }

    private void setAllowedQualities(String versionId, Set<QualityLevel> levels) {
        levelMapper.deleteByVersionId(versionId);
        for (QualityLevel level : levels.stream().sorted().toList()) {
            BizQualityUsagePolicyLevel row = new BizQualityUsagePolicyLevel();
            row.setPolicyLevelId(id());
            row.setVersionId(versionId);
            row.setQualityLevel(level.name());
            levelMapper.insert(row);
        }
    }

    private Set<QualityLevel> allowedQualities(String versionId) {
        LinkedHashSet<QualityLevel> result = new LinkedHashSet<>();
        for (BizQualityUsagePolicyLevel level : levelMapper.selectByVersionId(versionId)) {
            result.add(QualityLevel.valueOf(level.getQualityLevel()));
        }
        return Set.copyOf(result);
    }

    private Set<QualityLevel> parseLevels(List<String> rawLevels) {
        if (rawLevels == null) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED, "allowedQualities 不能为空");
        }
        LinkedHashSet<QualityLevel> levels = new LinkedHashSet<>();
        for (String rawLevel : rawLevels) {
            try {
                QualityLevel level = QualityLevel.valueOf(requireText(rawLevel, "质量等级不能为空")
                        .toUpperCase(Locale.ROOT));
                if (!levels.add(level)) {
                    throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED,
                            "allowedQualities 不能包含重复等级");
                }
            } catch (IllegalArgumentException exception) {
                throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED,
                        "allowedQualities 只能包含 Q0、Q1 或 Q2");
            }
        }
        validateAllowedQualities(levels);
        return Set.copyOf(levels);
    }

    private void validateAllowedQualities(Set<QualityLevel> levels) {
        if (!levels.isEmpty() && !levels.contains(QualityLevel.Q0)) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED,
                    "非空 allowedQualities 必须包含 Q0");
        }
    }

    private List<String> qualityNames(Set<QualityLevel> levels) {
        return levels.stream().sorted().map(Enum::name).toList();
    }

    private ScenarioView scenarioView(BizQualityUsageScenario scenario) {
        return new ScenarioView(scenario.getScenarioCode(), scenario.getScenarioName(), scenario.getAdapterType(),
                scenario.getStatus(), scenario.getIntroducedVersion());
    }

    private PolicyView activePolicyView(BizQualityUsagePolicy policy, BizQualityUsageScenario scenario) {
        BizQualityUsagePolicyVersion active = policy.getCurrentActiveVersionId() == null
                ? null : versionMapper.selectById(policy.getCurrentActiveVersionId());
        return new PolicyView(policy.getPolicyId(), policy.getBuildingId(), policy.getPointId(),
                scenario.getScenarioCode(), scenario.getScenarioName(),
                active == null ? "SYSTEM_DEFAULT_Q0_ONLY" : "PUBLISHED_POLICY",
                active == null ? null : active.getVersionNo(),
                active == null ? List.of("Q0") : qualityNames(allowedQualities(active.getVersionId())),
                active == null ? null : active.getEffectiveFromMs(),
                active == null ? null : active.getEffectiveToMs(),
                active == null ? "NO_ACTIVE_POLICY" : active.getStatus());
    }

    private PolicyVersionView versionView(BizQualityUsagePolicyVersion version) {
        return new PolicyVersionView(version.getVersionId(), version.getPolicyId(), version.getChangeSetId(),
                version.getVersionNo(), version.getStatus(), version.getBaseActiveVersionId(),
                version.getCopiedFromVersionId(), qualityNames(allowedQualities(version.getVersionId())),
                version.getEffectiveFromMs(), version.getEffectiveToMs(),
                Boolean.TRUE.equals(flag(version.getInitialBaseline())), version.getPublishedConfigRevision(),
                version.getChangeSource(), version.getChangeReason(), version.getCreatedBy(),
                epoch(version.getCreateTime()), version.getPublishedBy(), epoch(version.getPublishedAt()),
                epoch(version.getRetiredAt()));
    }

    private ChangeSetView changeSetView(BizQualityUsageChangeSet changeSet) {
        List<PolicyVersionView> versions = versionMapper.selectByChangeSetId(changeSet.getChangeSetId()).stream()
                .sorted(Comparator.comparing(BizQualityUsagePolicyVersion::getVersionNo))
                .map(this::versionView)
                .toList();
        return new ChangeSetView(changeSet.getChangeSetId(), changeSet.getBuildingId(), changeSet.getStatus(),
                changeSet.getRevision(), changeSet.getSubmittedRevision(), changeSet.getCreatedBy(),
                Boolean.TRUE.equals(flag(changeSet.getHasBeenSubmitted())), changeSet.getTitle(),
                changeSet.getDescription(), changeSet.getLastFailureCode(), epoch(changeSet.getCreateTime()),
                epoch(changeSet.getUpdateTime()), epoch(changeSet.getSubmittedAt()), epoch(changeSet.getPublishedAt()),
                epoch(changeSet.getCancelledAt()), versions);
    }

    private ReviewRequestView reviewView(BizQualityUsageReviewRequest review, BizQualityUsageChangeSet changeSet) {
        return new ReviewRequestView(review.getRequestId(), review.getChangeSetId(), changeSet.getBuildingId(),
                review.getRequestNo(), review.getStatus(), review.getReviewMode(), review.getSubmittedRevision(),
                review.getSnapshotSha256(), review.getSubmittedBy(), epoch(review.getSubmittedAt()),
                review.getReviewerId(), review.getReviewComment(), epoch(review.getReviewedAt()),
                review.getWithdrawnBy(), epoch(review.getWithdrawnAt()));
    }

    private AuditView auditView(BizQualityUsageAuditLog audit) {
        return new AuditView(audit.getAuditId(), audit.getBuildingId(), audit.getOperatorId(), audit.getActionType(),
                audit.getObjectType(), audit.getObjectId(), audit.getVersionId(), audit.getBeforeSummary(),
                audit.getAfterSummary(), audit.getResult(), audit.getReasonCode(), audit.getConfigRevision(),
                epoch(audit.getOperationTime()));
    }

    private void audit(Long operatorId, String buildingId, String action, String objectType, String objectId,
                       String versionId, String before, String after, String result, String reasonCode,
                       Long configRevision, String idempotencyKey, String requestSha256) {
        BizQualityUsageAuditLog audit = new BizQualityUsageAuditLog();
        audit.setAuditId(id());
        audit.setBuildingId(buildingId);
        audit.setActorType(operatorId == null ? "SYSTEM_MIGRATION" : "USER");
        audit.setOperatorId(operatorId);
        audit.setActionType(action);
        audit.setObjectType(objectType);
        audit.setObjectId(objectId);
        audit.setVersionId(versionId);
        audit.setReviewRequestId("REVIEW_REQUEST".equals(objectType) ? objectId : null);
        audit.setBeforeSummary(bounded(before, 1000));
        audit.setAfterSummary(bounded(after, 1000));
        audit.setResult(result);
        audit.setReasonCode(reasonCode);
        audit.setTraceId(TraceContext.current());
        audit.setEnvironmentMode(auditProperties.getEnvironmentMode().name());
        audit.setSelfApprovalDevMode("SELF_APPROVAL_DEV_MODE".equals(action));
        audit.setConfigRevision(configRevision);
        audit.setIdempotencyKey(idempotencyKey);
        audit.setRequestSha256(requestSha256);
        audit.setOperationTime(now());
        auditMapper.insert(audit);
    }

    /**
     * 调用方必须先锁定业务对象并完成建筑范围校验：这样重放既能看见先提交事务的审计，
     * 也不会让猜中的幂等键绕过私有草稿或审核结果的可见性边界。
     */
    private BizQualityUsageAuditLog replay(String idempotencyKey, String requestSha256) {
        BizQualityUsageAuditLog existing = auditMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing == null) {
            return null;
        }
        if (!Objects.equals(existing.getRequestSha256(), requestSha256)) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.IDEMPOTENCY_REUSED,
                    "Idempotency-Key 已用于不同请求");
        }
        return existing;
    }

    private BizQualityUsagePolicy requirePolicy(String policyId) {
        BizQualityUsagePolicy policy = policyMapper.selectById(policyId);
        if (policy == null) {
            throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "质量使用策略不存在或不可见");
        }
        return policy;
    }

    private BizQualityUsageChangeSet requireChangeSet(String changeSetId) {
        BizQualityUsageChangeSet changeSet = changeSetMapper.selectById(changeSetId);
        if (changeSet == null) {
            throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "质量使用变更集不存在或不可见");
        }
        return changeSet;
    }

    private BizQualityUsageChangeSet requireLockedChangeSet(String changeSetId) {
        BizQualityUsageChangeSet changeSet = changeSetMapper.selectByIdForUpdate(changeSetId);
        if (changeSet == null) {
            throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "质量使用变更集不存在或不可见");
        }
        return changeSet;
    }

    private BizQualityUsageReviewRequest requireReview(String requestId) {
        BizQualityUsageReviewRequest review = reviewMapper.selectById(requestId);
        if (review == null) {
            throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "质量使用审核申请不存在或不可见");
        }
        return review;
    }

    private BizQualityUsageReviewRequest requireLockedReview(String requestId) {
        BizQualityUsageReviewRequest review = reviewMapper.selectByIdForUpdate(requestId);
        if (review == null) {
            throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "质量使用审核申请不存在或不可见");
        }
        return review;
    }

    private BizQualityUsageReviewRequest requirePendingReview(BizQualityUsageChangeSet changeSet) {
        BizQualityUsageReviewRequest review = reviewMapper.selectPendingByChangeSetForUpdate(changeSet.getChangeSetId());
        if (review == null) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.PENDING_CONFLICT, "变更集没有待审核申请");
        }
        return review;
    }

    private BizQualityUsageChangeSet requireChangeSetByReview(String reviewId) {
        return requireChangeSet(requireReview(reviewId).getChangeSetId());
    }

    private BizQualityUsagePolicyVersion requireVersion(String versionId) {
        BizQualityUsagePolicyVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "策略版本不存在或不可见");
        }
        return version;
    }

    private BizQualityUsageScenario requireScenario(String scenarioCode) {
        BizQualityUsageScenario scenario = scenarioMapper.selectByCode(scenarioCode);
        if (scenario == null) {
            throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "质量使用场景不存在");
        }
        return scenario;
    }

    private BizQualityUsageScenario requireScenarioById(String scenarioId) {
        BizQualityUsageScenario scenario = scenarioMapper.selectById(scenarioId);
        if (scenario == null) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "策略引用的质量使用场景不存在");
        }
        return scenario;
    }

    private BizDataPoint requireEligiblePoint(String pointId, String buildingId) {
        BizDataPoint point = pointMapper.selectByIdForUpdate(pointId);
        if (point == null || !Objects.equals(point.getBuildingId(), buildingId) || !"ONLINE".equals(point.getStatus())) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.POINT_NOT_ELIGIBLE, "测点当前不可配置质量使用策略");
        }
        return point;
    }

    private Map<String, BizQualityUsageScenario> scenarioById() {
        return scenarioMapper.selectList(new LambdaQueryWrapper<BizQualityUsageScenario>()).stream()
                .collect(Collectors.toMap(BizQualityUsageScenario::getScenarioId, value -> value));
    }

    private Comparator<BizQualityUsagePolicy> policyOrder(Map<String, BizQualityUsageScenario> scenarios) {
        return Comparator.comparing(BizQualityUsagePolicy::getPointId)
                .thenComparing(policy -> {
                    BizQualityUsageScenario scenario = scenarios.get(policy.getScenarioId());
                    return scenario == null ? "" : scenario.getScenarioCode();
                });
    }

    private boolean versionVisible(BizQualityUsagePolicyVersion version, BizQualityUsagePolicy policy,
                                   Long userId, Collection<String> roles) {
        if (isAdmin(roles) || Objects.equals(version.getVersionId(), policy.getCurrentActiveVersionId())) {
            return true;
        }
        if (!hasRole(roles, FormalRole.ENERGY_MANAGER)) {
            return false;
        }
        if (version.getChangeSetId() == null) {
            return false;
        }
        BizQualityUsageChangeSet changeSet = changeSetMapper.selectById(version.getChangeSetId());
        return changeSet != null && Objects.equals(changeSet.getCreatedBy(), userId);
    }

    private boolean changeSetVisible(BizQualityUsageChangeSet changeSet, Long userId, Collection<String> roles) {
        return isAdmin(roles) || Objects.equals(changeSet.getCreatedBy(), userId);
    }

    private boolean reviewVisible(BizQualityUsageReviewRequest review, BizQualityUsageChangeSet changeSet,
                                  Long userId, Collection<String> roles) {
        return isAdmin(roles) || Objects.equals(changeSet.getCreatedBy(), userId)
                || Objects.equals(review.getSubmittedBy(), userId);
    }

    private void checkChangeSetVisible(BizQualityUsageChangeSet changeSet, Long userId, Collection<String> roles) {
        checkBuildingAccess(userId, roles, changeSet.getBuildingId());
        if (!changeSetVisible(changeSet, userId, roles)) {
            notVisible();
        }
    }

    private void requireChangeSetEditor(BizQualityUsageChangeSet changeSet, Long userId, Collection<String> roles) {
        checkBuildingAccess(userId, roles, changeSet.getBuildingId());
        if (!Objects.equals(changeSet.getCreatedBy(), userId)) {
            // 平台管理员拥有全局查看和审核权，但不应修改能源管理员的私有草稿。
            notVisible();
        }
    }

    private void checkBuildingAccess(Long userId, Collection<String> roles, String buildingId) {
        if (!buildingScopeService.canAccess(userId, roles, buildingId)) {
            throw QualityUsageErrors.error(403, QualityUsageErrors.FORBIDDEN, "没有该建筑的数据权限");
        }
    }

    private void deleteNeverSubmittedDrafts(String changeSetId) {
        for (BizQualityUsagePolicyVersion version : versionMapper.selectByChangeSetId(changeSetId)) {
            if (!PolicyVersionStatus.DRAFT.name().equals(version.getStatus())) {
                throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, "变更集含有正式版本，不能删除");
            }
            levelMapper.deleteByVersionId(version.getVersionId());
        }
        versionMapper.deleteDraftsByChangeSet(changeSetId);
    }

    private static void requireReader(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.BUILDING_OWNER)
                && !hasRole(roles, FormalRole.ENERGY_MANAGER) && !isAdmin(roles)) {
            forbidden();
        }
    }

    private static void requireMaintainer(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.ENERGY_MANAGER) && !isAdmin(roles)) {
            forbidden();
        }
    }

    private static void requireAdmin(Collection<String> roles) {
        if (!isAdmin(roles)) {
            forbidden();
        }
    }

    private static boolean hasRole(Collection<String> roles, FormalRole role) {
        return roles != null && roles.stream().anyMatch(value -> role.name().equalsIgnoreCase(value));
    }

    private static boolean isAdmin(Collection<String> roles) {
        return hasRole(roles, FormalRole.PLATFORM_ADMIN);
    }

    private static void requireState(String actual, String expected, String message) {
        if (!Objects.equals(actual, expected)) {
            throw QualityUsageErrors.error(409, QualityUsageErrors.STATE_CONFLICT, message);
        }
    }

    private static void forbidden() {
        throw QualityUsageErrors.error(403, QualityUsageErrors.FORBIDDEN, "没有质量使用治理权限");
    }

    private static void notVisible() {
        throw QualityUsageErrors.error(404, QualityUsageErrors.NOT_FOUND, "对象不存在或不可见");
    }

    private static BusinessException versionConflict() {
        return QualityUsageErrors.error(409, QualityUsageErrors.VERSION_CONFLICT, "策略基础版本已经变化");
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        String key = trimToNull(idempotencyKey);
        if (key == null || key.length() > 160) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED,
                    "Idempotency-Key 不能为空且长度不能超过 160");
        }
        return key;
    }

    private static String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED, message);
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw QualityUsageErrors.error(400, QualityUsageErrors.VALIDATION_FAILED, "分页参数不合法");
        }
    }

    private static <T> PageResponse<T> page(List<T> values, int page, int size) {
        int from = Math.min((page - 1) * size, values.size());
        int to = Math.min(from + size, values.size());
        return new PageResponse<>(page, size, values.size(), values.subList(from, to));
    }

    private static long databaseNowFallback() {
        return System.currentTimeMillis();
    }

    private long databaseNowMs() {
        Long value = configRevisionMapper.selectDatabaseEpochMs();
        return value == null ? databaseNowFallback() : value;
    }

    private static long nextCompleteMinute(long timestampMs) {
        return QualityUsagePolicyResolver.alignMinute(timestampMs) + 60_000L;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(MYSQL_ZONE);
    }

    private static Long epoch(LocalDateTime value) {
        return value == null ? null : value.atZone(MYSQL_ZONE).toInstant().toEpochMilli();
    }

    private static Boolean flag(Integer value) {
        return value != null && value != 0;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String changeSetSummary(BizQualityUsageChangeSet changeSet) {
        return "status=" + changeSet.getStatus() + ";revision=" + changeSet.getRevision();
    }

    private record NormalizedDraft(String pointId, String scenarioCode, Set<QualityLevel> allowedQualities,
                                   String copiedFromVersionId, String changeReason) {
        private String identity() {
            return pointId + "|" + scenarioCode;
        }
    }

    private record PolicyEnvelope(BizQualityUsagePolicy policy, BizQualityUsageScenario scenario,
                                  BizQualityUsagePolicyVersion version, Set<QualityLevel> levels) {
    }

    private record Snapshot(String json, String sha256) {
    }

    private record ReviewAndChangeSet(BizQualityUsageReviewRequest review,
                                      BizQualityUsageChangeSet changeSet) {
    }
}
