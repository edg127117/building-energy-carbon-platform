package com.platform.energy.period;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.energy.period.EnergyPeriodModels.*;
import com.platform.energy.period.api.EnergyPeriodContracts.*;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import java.util.UUID;
import java.util.stream.Collectors;

import static com.platform.energy.period.EnergyPeriodCalculationService.TCE_RULE_UNAVAILABLE;
import static com.platform.energy.period.EnergyPeriodErrors.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 编排研发周期当前投影、月度审核封账和最多一百项的受控重算批次。
 * 封账快照不可覆盖；重算只追加实质变化，且整个批次完成后新快照才对查询可见。
 */
public class EnergyPeriodLifecycleService {
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;
    private static final Set<String> PINNED_RULE_KEYS = Set.of(
            "periodPolicyVersionId", "relationVersionId", "pointBindingVersionId",
            "qualityPolicyVersions", "integrationPolicyVersionId", "energyItemVersionId",
            "inputUnitVersionId", "applicableInputUnitVersionId", "parameterVersionId",
            "formulaVersionId", "standardCoalLhvVersionId", "algorithmCode");

    private final EnergyPeriodAuthorization authorization;
    private final EnergyPeriodGovernanceService governanceService;
    private final EnergyPeriodResultRepository repository;
    private final EnergyPeriodCalculationService calculationService;
    private final EnergyPeriodValueStore valueStore;
    private final ObjectMapper objectMapper;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    @Transactional(rollbackFor = Exception.class)
    public ProjectionView refresh(
            long userId, Collection<String> roles, RefreshProjectionRequest request) {
        authorization.requireCalculation(userId, roles);
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        String pointId = text(request.pointId(), 32, "测点编码无效");
        authorization.checkBuilding(userId, roles, buildingId);
        PeriodPolicyVersion policy = governanceService.effectivePolicy(
                buildingId, request.periodDate().atStartOfDay());
        PeriodWindow window = EnergyPeriodBoundary.resolve(
                request.periodType(), request.periodDate(), policy.timezoneId());
        Instant calculationAsOf = request.calculationAsOf() == null
                ? Instant.now() : request.calculationAsOf();
        if (calculationAsOf.isBefore(window.startInclusive())) {
            validation("计算水位不能早于周期起点");
        }
        ConversionSelection selection = selection(request.conversion());
        ProjectionCalculation calculation = window.type() == PeriodType.YEAR
                ? calculateYear(buildingId, pointId, window, policy)
                : calculationService.calculate(userId, roles, buildingId, pointId, window, policy,
                selection, calculationAsOf);
        CurrentProjection value = publishCurrent(calculation, calculationAsOf);
        audit(userId, buildingId, "REFRESH_ENERGY_PERIOD_CURRENT", "ENERGY_PERIOD_CURRENT",
                value.projectionId(), null, "revision=" + value.revision()
                        + ";nature=" + value.resultNature() + ";issues=" + value.issueCodes());
        return view(value);
    }

    public ProjectionView current(
            long userId, Collection<String> roles, String projectionId) {
        authorization.requireReader(roles);
        CurrentProjection value = requireCurrent(projectionId);
        authorization.checkBuilding(userId, roles, value.buildingId());
        return view(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public LockRequestView submitLock(
            long userId, Collection<String> roles, SubmitLockRequest request) {
        authorization.requireLockSubmitter(userId, roles);
        CurrentProjection current = requireCurrent(request.projectionId());
        authorization.checkBuilding(userId, roles, current.buildingId());
        if (current.revision() != request.expectedRevision()) versionConflict();
        PolicyResolution resolution = resolveLock(current,
                text(request.evidenceReference(), 500, "封账证据引用无效"), Instant.now());
        LockRequest value = new LockRequest(id(), current.projectionId(), current.revision(),
                current.buildingId(), ReviewStatus.PENDING_REVIEW.name(),
                csv(resolution.policyVersionIds()), text(request.reason(), 500, "封账原因无效"),
                request.evidenceReference().trim(), userId, LocalDateTime.now(), null, null, null);
        repository.insertLockRequest(value);
        audit(userId, current.buildingId(), "SUBMIT_ENERGY_PERIOD_LOCK", "ENERGY_PERIOD_LOCK",
                value.requestId(), null, "projection=" + current.projectionId()
                        + ";revision=" + current.revision() + ";policies="
                        + value.issuePolicyVersions());
        return view(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public SnapshotView approveLock(
            long userId, Collection<String> roles, String requestId,
            ApproveLockRequest request) {
        authorization.requireLockReviewer(userId, roles);
        LockRequest lock = requireLock(requestId);
        authorization.checkBuilding(userId, roles, lock.buildingId());
        authorization.requireSeparation(lock.submittedBy(), userId);
        if (!ReviewStatus.PENDING_REVIEW.name().equals(lock.status())) statusConflict("封账申请已处理");
        CurrentProjection current = requireCurrent(lock.projectionId());
        if (current.revision() != lock.projectionRevision()) {
            throw error(409, VERSION_CONFLICT, "当前投影已变化，请重新提交封账申请");
        }
        PolicyResolution resolution = resolveLock(current, lock.evidenceReference(), Instant.now());
        if (!csv(resolution.policyVersionIds()).equals(lock.issuePolicyVersions())) {
            throw error(409, VERSION_CONFLICT, "封账例外策略已变化，请重新提交申请");
        }
        int snapshotVersion = repository.nextSnapshotVersion(current.projectionId());
        String snapshotId = id();
        String evidenceJson = lockEvidence(current, lock, resolution);
        String evidenceHash = EnergyPeriodCalculationService.digest(evidenceJson);
        String resultKey = digest("SNAPSHOT|" + current.projectionId() + "|" + snapshotVersion
                + "|" + evidenceHash);
        LocalDateTime now = LocalDateTime.now();
        PeriodSnapshot snapshot = snapshot(current, snapshotId, resultKey, snapshotVersion,
                resolution.status().name(), csv(resolution.policyVersionIds()), evidenceJson,
                evidenceHash, null, null, userId, now);
        valueStore.write(numeric(snapshot, snapshotVersion));
        repository.insertSnapshot(snapshot);
        if (repository.approveLockRequest(lock.requestId(), userId, now,
                text(request.reviewComment(), 500, "审核意见无效")) != 1) {
            versionConflict();
        }
        audit(userId, current.buildingId(), "APPROVE_ENERGY_PERIOD_LOCK",
                "ENERGY_PERIOD_SNAPSHOT", snapshot.snapshotId(), null,
                "version=" + snapshot.snapshotVersion() + ";status=" + snapshot.status()
                        + ";nature=" + snapshot.resultNature());
        return view(snapshot);
    }

    public SnapshotView snapshot(
            long userId, Collection<String> roles, String projectionId) {
        authorization.requireReader(roles);
        CurrentProjection current = requireCurrent(projectionId);
        authorization.checkBuilding(userId, roles, current.buildingId());
        PeriodSnapshot value = repository.findVisibleSnapshot(current.projectionId());
        if (value == null) throw error(404, NOT_FOUND, "周期尚无可见封账快照");
        return view(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public RecalculationBatchView submitRecalculation(
            long userId, Collection<String> roles, SubmitRecalculationRequest request) {
        authorization.requireRecalculationSubmitter(userId, roles);
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.checkBuilding(userId, roles, buildingId);
        RecalculationMode mode = parse(RecalculationMode.class, request.mode(), "重算模式无效");
        List<String> snapshotIds = request.snapshotIds().stream().map(String::trim)
                .distinct().sorted().toList();
        if (snapshotIds.isEmpty() || snapshotIds.size() > 100) validation("重算目标必须在1到100项之间");
        for (String snapshotId : snapshotIds) {
            PeriodSnapshot snapshot = requireSnapshot(snapshotId);
            if (!buildingId.equals(snapshot.buildingId()) || isTerminal(snapshot.status())) {
                validation("重算目标不属于当前建筑或不是当前可重算快照");
            }
            PeriodSnapshot visible = repository.findVisibleSnapshot(snapshot.projectionId());
            if (visible == null || !snapshot.snapshotId().equals(visible.snapshotId())) {
                validation("只能重算每个周期当前可见的最新快照");
            }
        }
        String reason = text(request.reason(), 500, "重算原因无效");
        String idempotencyKey = text(request.idempotencyKey(), 160, "幂等键无效");
        String requestHash = digest(buildingId + "|" + mode + "|" + reason + "|"
                + String.join(",", snapshotIds));
        RecalculationBatch existing = repository.findBatchByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            if (!requestHash.equals(existing.requestHash())) {
                throw error(409, IDEMPOTENCY_CONFLICT, "幂等键已用于不同的重算请求");
            }
            return view(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        RecalculationBatch batch = new RecalculationBatch(id(), buildingId, idempotencyKey,
                requestHash, mode.name(), BatchStatus.CREATED.name(), reason, snapshotIds.size(),
                0, 0, 0, 0, userId, now, null, null, null, null, null);
        repository.insertBatch(batch);
        List<RecalculationItem> items = new ArrayList<>();
        for (int index = 0; index < snapshotIds.size(); index++) {
            items.add(new RecalculationItem(id(), batch.batchId(), snapshotIds.get(index), index,
                    BatchItemStatus.PENDING.name(), null, null));
        }
        repository.insertBatchItems(items);
        if (repository.advanceBatch(batch.batchId(), BatchStatus.CREATED.name(),
                BatchStatus.PENDING_REVIEW.name()) != 1) versionConflict();
        RecalculationBatch created = requireBatch(batch.batchId());
        audit(userId, buildingId, "SUBMIT_ENERGY_RECALCULATION", "ENERGY_RECALCULATION_BATCH",
                batch.batchId(), null, "mode=" + mode + ";targets=" + snapshotIds.size());
        return view(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public RecalculationBatchView approveRecalculation(
            long userId, Collection<String> roles, String batchId,
            ApproveRecalculationRequest request) {
        authorization.requireRecalculationReviewer(userId, roles);
        RecalculationBatch batch = requireBatch(batchId);
        authorization.checkBuilding(userId, roles, batch.buildingId());
        authorization.requireSeparation(batch.submittedBy(), userId);
        if (repository.approveBatch(batch.batchId(), userId, LocalDateTime.now(),
                text(request.reviewComment(), 500, "审核意见无效")) != 1) {
            statusConflict("重算批次不是待审核状态");
        }
        RecalculationBatch approved = requireBatch(batch.batchId());
        audit(userId, batch.buildingId(), "APPROVE_ENERGY_RECALCULATION",
                "ENERGY_RECALCULATION_BATCH", batch.batchId(), null,
                "status=" + approved.status() + ";targets=" + approved.totalItems());
        return view(approved);
    }

    @Transactional(rollbackFor = Exception.class)
    public RecalculationBatchView executeRecalculation(
            long userId, Collection<String> roles, String batchId) {
        authorization.requireCalculation(userId, roles);
        RecalculationBatch batch = requireBatch(batchId);
        authorization.checkBuilding(userId, roles, batch.buildingId());
        if (!BatchStatus.VALIDATING.name().equals(batch.status())) {
            statusConflict("重算批次尚未审核或已经执行");
        }
        List<RecalculationItem> items = repository.listBatchItems(batch.batchId());
        validateBatchTargets(batch, items);
        advance(batch.batchId(), BatchStatus.VALIDATING, BatchStatus.CALCULATING);
        RecalculationMode mode = RecalculationMode.valueOf(batch.mode());
        for (RecalculationItem item : items) {
            try {
                recalculateItem(userId, roles, batch, item, mode);
            } catch (BusinessException | IllegalStateException exception) {
                repository.updateBatchItem(item.itemId(), BatchItemStatus.FAILED.name(), null,
                        safeError(exception));
            }
        }
        repository.refreshBatchCounters(batch.batchId());
        RecalculationBatch calculated = requireBatch(batch.batchId());
        if (calculated.failedItems() > 0) {
            repository.failBatch(batch.batchId(), "ENERGY_RECALCULATION_ITEM_FAILED",
                    LocalDateTime.now());
            audit(userId, batch.buildingId(), "FAIL_ENERGY_RECALCULATION",
                    "ENERGY_RECALCULATION_BATCH", batch.batchId(), null,
                    "failed=" + calculated.failedItems() + ";visible=false");
            return view(requireBatch(batch.batchId()));
        }
        advance(batch.batchId(), BatchStatus.CALCULATING, BatchStatus.WRITING_RESULTS);
        if (repository.completeBatch(batch.batchId(), LocalDateTime.now()) != 1) versionConflict();
        RecalculationBatch completed = requireBatch(batch.batchId());
        audit(userId, batch.buildingId(), "COMPLETE_ENERGY_RECALCULATION",
                "ENERGY_RECALCULATION_BATCH", batch.batchId(), null,
                "changed=" + completed.changedItems() + ";unchanged="
                        + completed.unchangedItems() + ";visible=true");
        return view(completed);
    }

    public RecalculationBatchView batch(
            long userId, Collection<String> roles, String batchId) {
        authorization.requireReader(roles);
        RecalculationBatch value = requireBatch(batchId);
        authorization.checkBuilding(userId, roles, value.buildingId());
        return view(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDirty(
            String buildingId, String pointId, String periodType, java.time.LocalDate periodDate,
            String reasonCode) {
        PeriodPolicyVersion policy = governanceService.effectivePolicy(
                buildingId, periodDate.atStartOfDay());
        PeriodWindow window = EnergyPeriodBoundary.resolve(periodType, periodDate, policy.timezoneId());
        repository.markDirty(id(), buildingId, pointId, window.type().name(),
                window.startInclusive(), window.endExclusive(),
                text(reasonCode, 64, "待重算原因无效").toUpperCase(), LocalDateTime.now());
    }

    private CurrentProjection publishCurrent(
            ProjectionCalculation calculation, Instant calculationAsOf) {
        PeriodWindow window = calculation.window();
        CurrentProjection previous = repository.findCurrent(calculation.buildingId(),
                calculation.pointId(), window.type().name(), window.startInclusive(),
                window.endExclusive());
        long revision = previous == null ? 1 : previous.revision() + 1;
        String projectionId = previous == null
                ? digest("PROJECTION|" + calculation.buildingId() + "|" + calculation.pointId()
                + "|" + window.type() + "|" + window.startInclusive()).substring(0, 32)
                : previous.projectionId();
        String resultKey = digest("CURRENT|" + projectionId);
        String status = calculationAsOf.isBefore(window.endExclusive())
                ? CurrentStatus.OPEN.name() : CurrentStatus.PROVISIONAL.name();
        LocalDateTime now = LocalDateTime.now();
        CurrentProjection value = new CurrentProjection(projectionId, resultKey,
                calculation.buildingId(), calculation.pointId(), window.type().name(),
                window.startInclusive(), window.endExclusive(), window.timezoneId(),
                calculation.periodPolicyVersionId(), status, revision, calculation.resultNature(),
                calculation.energyItemCode(), calculation.nativeQuantity(),
                calculation.nativeUnitCode(), calculation.tce(), calculation.tceUnitCode(),
                calculation.coverageRatio(), csv(calculation.issueCodes()),
                calculation.evidenceJson(), calculation.evidenceHash(),
                calculation.conversionSelectionJson(), calculation.activityWatermark(),
                calculation.calculatedAt(), now);
        valueStore.write(numeric(value));
        if (previous == null) {
            repository.insertCurrent(value);
        } else if (repository.updateCurrent(value, previous.revision()) != 1) {
            versionConflict();
        }
        return value;
    }

    private ProjectionCalculation calculateYear(
            String buildingId, String pointId, PeriodWindow window,
            PeriodPolicyVersion policy) {
        List<PeriodSnapshot> months = repository.listVisibleMonthlySnapshots(
                buildingId, pointId, window.startInclusive(), window.endExclusive());
        List<String> issues = new ArrayList<>();
        if (months.size() != 12) issues.add("YEAR_MONTHS_INCOMPLETE");
        String energyItem = common(months.stream().map(PeriodSnapshot::energyItemCode).toList(),
                "年度月快照能源品种不一致");
        String unit = common(months.stream().map(PeriodSnapshot::nativeUnitCode).toList(),
                "年度月快照原始单位不一致");
        BigDecimal quantity = months.stream().map(PeriodSnapshot::nativeQuantity)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, CALCULATION_CONTEXT));
        boolean completeTce = months.size() == 12 && months.stream().allMatch(value -> value.tce() != null);
        BigDecimal tce = completeTce ? months.stream().map(PeriodSnapshot::tce)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, CALCULATION_CONTEXT)) : null;
        String tceUnit = completeTce
                ? common(months.stream().map(PeriodSnapshot::tceUnitCode).toList(),
                "年度月快照tce单位不一致") : null;
        if (!completeTce) issues.add(TCE_RULE_UNAVAILABLE);
        BigDecimal coverage = months.isEmpty() ? BigDecimal.ZERO
                : months.stream().map(PeriodSnapshot::coverageRatio).reduce(BigDecimal.ZERO,
                (left, right) -> left.add(right, CALCULATION_CONTEXT))
                .divide(BigDecimal.valueOf(months.size()), CALCULATION_CONTEXT);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("periodPolicyVersionId", policy.versionId());
        evidence.put("timezoneId", policy.timezoneId());
        evidence.put("monthlySnapshotIds", months.stream().map(PeriodSnapshot::snapshotId).toList());
        evidence.put("monthlyEvidenceHashes", months.stream().map(PeriodSnapshot::evidenceHash).toList());
        String json = json(evidence);
        Instant watermark = months.stream().map(PeriodSnapshot::activityWatermark)
                .max(Comparator.naturalOrder()).orElse(window.startInclusive());
        return new ProjectionCalculation("DEVELOPMENT_SIMULATION", buildingId, pointId, window,
                policy.versionId(), energyItem == null ? "UNAVAILABLE" : energyItem, quantity,
                unit == null ? "UNAVAILABLE" : unit, tce, tceUnit, coverage,
                issues.stream().distinct().toList(), json, digest(json), null, watermark,
                LocalDateTime.now());
    }

    private PolicyResolution resolveLock(
            CurrentProjection current, String evidenceReference, Instant now) {
        if (!PeriodType.MONTH.name().equals(current.periodType())) {
            throw error(409, LOCK_BLOCKED, "首版只允许月度正式封账");
        }
        PeriodPolicyVersion policy = governanceService.periodPolicyVersion(
                current.periodPolicyVersionId());
        Instant earliest = current.endExclusive().plus(policy.closingDelayHours(), ChronoUnit.HOURS);
        if (now.isBefore(earliest)) {
            throw error(409, LOCK_BLOCKED, "月度周期尚未结束迟到窗口");
        }
        List<String> issues = split(current.issueCodes());
        if (issues.isEmpty()) return new PolicyResolution(List.of(), SnapshotStatus.LOCKED_COMPLETE);
        LocalDateTime at = LocalDateTime.ofInstant(current.endExclusive().minusNanos(1),
                ZoneId.of(current.timezoneId()));
        List<ExceptionPolicyVersion> policies = governanceService.effectiveExceptionPolicies(
                current.buildingId(), issues, at);
        Map<String, List<ExceptionPolicyVersion>> byIssue = policies.stream()
                .collect(Collectors.groupingBy(ExceptionPolicyVersion::issueCode));
        List<String> versions = new ArrayList<>();
        SnapshotStatus status = SnapshotStatus.LOCKED_WITH_EXCEPTIONS;
        for (String issue : issues) {
            List<ExceptionPolicyVersion> matches = byIssue.getOrDefault(issue, List.of());
            if (matches.size() != 1) lockBlocked("问题" + issue + "缺少唯一已审核例外策略");
            ExceptionPolicyVersion policyVersion = matches.getFirst();
            LockAction action = LockAction.valueOf(policyVersion.lockAction());
            if (action == LockAction.BLOCK) lockBlocked("例外策略要求阻塞问题" + issue);
            if (policyVersion.maximumAffectedCount() != null
                    && policyVersion.maximumAffectedCount() < 1) lockBlocked("影响数量超过例外阈值");
            if (policyVersion.maximumAffectedRatio() != null
                    && policyVersion.maximumAffectedRatio().compareTo(BigDecimal.ONE) < 0) {
                lockBlocked("单测点影响比例超过例外阈值");
            }
            if (policyVersion.minimumCoverageRatio() != null
                    && current.coverageRatio().compareTo(policyVersion.minimumCoverageRatio()) < 0) {
                lockBlocked("数据覆盖率低于例外策略阈值");
            }
            if (TCE_RULE_UNAVAILABLE.equals(issue)
                    && action != LockAction.LOCK_NATIVE_QUANTITY_ONLY) {
                lockBlocked("折标规则缺失时只能封存原始能源量");
            }
            if (policyVersion.requiresApproval() && evidenceReference.isBlank()) {
                lockBlocked("例外策略要求审核证据");
            }
            if (action == LockAction.EXCLUDE_AFFECTED_AND_LOCK
                    || action == LockAction.LOCK_NATIVE_QUANTITY_ONLY) {
                status = SnapshotStatus.LOCKED_PARTIAL;
            }
            versions.add(policyVersion.versionId());
        }
        return new PolicyResolution(versions.stream().sorted().toList(), status);
    }

    private void validateBatchTargets(
            RecalculationBatch batch, List<RecalculationItem> items) {
        if (items.size() != batch.totalItems() || items.size() > 100) {
            throw error(409, STATUS_CONFLICT, "重算批次目标数量不一致");
        }
        for (RecalculationItem item : items) {
            PeriodSnapshot source = requireSnapshot(item.sourceSnapshotId());
            PeriodSnapshot visible = repository.findVisibleSnapshot(source.projectionId());
            if (!batch.buildingId().equals(source.buildingId()) || visible == null
                    || !source.snapshotId().equals(visible.snapshotId())) {
                throw error(409, VERSION_CONFLICT, "重算前目标快照已变化");
            }
        }
    }

    private void recalculateItem(
            long userId, Collection<String> roles, RecalculationBatch batch,
            RecalculationItem item, RecalculationMode mode) {
        PeriodSnapshot source = requireSnapshot(item.sourceSnapshotId());
        if (PeriodType.YEAR.name().equals(source.periodType())) {
            throw error(409, STATUS_CONFLICT, "年度结果由月度封账汇总刷新，不进入本批次");
        }
        PeriodPolicyVersion policy = governanceService.periodPolicyVersion(
                source.periodPolicyVersionId());
        PeriodWindow window = new PeriodWindow(PeriodType.valueOf(source.periodType()),
                source.startInclusive(), source.endExclusive(), source.timezoneId());
        ConversionSelection selection = calculationService.readSelection(
                source.conversionSelectionJson());
        ProjectionCalculation result = calculationService.calculate(userId, roles,
                source.buildingId(), source.pointId(), window, policy, selection, Instant.now());
        if (mode == RecalculationMode.SAME_RULES
                && !sameRules(source.evidenceJson(), result.evidenceJson())) {
            throw error(409, HISTORICAL_RULE_CONFLICT,
                    "原规则证据已变化，请改用明确的历史重述申请");
        }
        CurrentProjection candidate = current(source, result);
        PolicyResolution resolution = resolveRecalculationPolicy(candidate, source, batch, mode);
        String comparison = contentHash(candidate, resolution.status(), result.evidenceHash(),
                csv(resolution.policyVersionIds()));
        String oldComparison = contentHash(source);
        if (comparison.equals(oldComparison)) {
            repository.updateBatchItem(item.itemId(), BatchItemStatus.UNCHANGED.name(), null, null);
            return;
        }
        String evidenceJson = recalculationEvidence(source, result, batch, resolution);
        String evidenceHash = digest(evidenceJson);
        int version = repository.nextSnapshotVersion(source.projectionId());
        String snapshotId = id();
        String resultKey = digest("RECALC|" + batch.batchId() + "|" + source.snapshotId());
        PeriodSnapshot snapshot = snapshot(candidate, snapshotId, resultKey, version,
                resolution.status().name(), csv(resolution.policyVersionIds()), evidenceJson,
                evidenceHash, source.snapshotId(), batch.batchId(), userId, LocalDateTime.now());
        valueStore.write(numeric(snapshot, version));
        repository.insertSnapshot(snapshot);
        repository.updateBatchItem(item.itemId(), BatchItemStatus.CHANGED.name(), snapshotId, null);
    }

    private PolicyResolution resolveRecalculationPolicy(
            CurrentProjection candidate, PeriodSnapshot source,
            RecalculationBatch batch, RecalculationMode mode) {
        PolicyResolution resolution = resolveLock(candidate,
                "recalculationBatch=" + batch.batchId(), Instant.MAX);
        if (mode == RecalculationMode.SAME_RULES
                && !csv(resolution.policyVersionIds()).equals(source.exceptionPolicyVersions())) {
            throw error(409, HISTORICAL_RULE_CONFLICT,
                    "原封账例外策略已变化，请改用明确的历史重述申请");
        }
        return resolution;
    }

    private CurrentProjection current(PeriodSnapshot source, ProjectionCalculation result) {
        return new CurrentProjection(source.projectionId(), source.resultKey(), source.buildingId(),
                source.pointId(), source.periodType(), source.startInclusive(), source.endExclusive(),
                source.timezoneId(), result.periodPolicyVersionId(), CurrentStatus.PROVISIONAL.name(),
                source.snapshotVersion(), result.resultNature(), result.energyItemCode(),
                result.nativeQuantity(), result.nativeUnitCode(), result.tce(), result.tceUnitCode(),
                result.coverageRatio(), csv(result.issueCodes()), result.evidenceJson(),
                result.evidenceHash(), result.conversionSelectionJson(), result.activityWatermark(),
                result.calculatedAt(), LocalDateTime.now());
    }

    private String lockEvidence(
            CurrentProjection current, LockRequest lock, PolicyResolution resolution) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("calculationEvidence", readMap(current.evidenceJson()));
        value.put("calculationEvidenceHash", current.evidenceHash());
        value.put("lockRequestId", lock.requestId());
        value.put("lockReason", lock.reason());
        value.put("lockEvidenceReference", lock.evidenceReference());
        value.put("exceptionPolicyVersions", resolution.policyVersionIds());
        return json(value);
    }

    private String recalculationEvidence(
            PeriodSnapshot source, ProjectionCalculation result, RecalculationBatch batch,
            PolicyResolution resolution) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("calculationEvidence", readMap(result.evidenceJson()));
        value.put("calculationEvidenceHash", result.evidenceHash());
        value.put("sourceSnapshotId", source.snapshotId());
        value.put("recalculationBatchId", batch.batchId());
        value.put("recalculationMode", batch.mode());
        value.put("recalculationReason", batch.reason());
        value.put("exceptionPolicyVersions", resolution.policyVersionIds());
        return json(value);
    }

    private boolean sameRules(String oldEvidence, String newEvidence) {
        Map<String, Object> oldMap = calculationEvidence(readMap(oldEvidence));
        Map<String, Object> newMap = calculationEvidence(readMap(newEvidence));
        for (String key : PINNED_RULE_KEYS) {
            if (!Objects.equals(oldMap.get(key), newMap.get(key))) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> calculationEvidence(Map<String, Object> value) {
        Object nested = value.get("calculationEvidence");
        return nested instanceof Map<?, ?> map ? (Map<String, Object>) map : value;
    }

    private String contentHash(
            CurrentProjection value, SnapshotStatus status, String evidenceHash,
            String exceptionPolicies) {
        return digest(status + "|" + value.energyItemCode() + "|" + value.nativeQuantity()
                + "|" + value.nativeUnitCode() + "|" + value.tce() + "|" + value.tceUnitCode()
                + "|" + value.coverageRatio() + "|" + value.issueCodes() + "|" + evidenceHash
                + "|" + exceptionPolicies);
    }

    private String contentHash(PeriodSnapshot value) {
        Map<String, Object> evidence = readMap(value.evidenceJson());
        Object persistedHash = evidence.get("calculationEvidenceHash");
        String calculationHash = persistedHash instanceof String hash && !hash.isBlank()
                ? hash
                : digest(json(calculationEvidence(evidence)));
        return digest(value.status() + "|" + value.energyItemCode() + "|" + value.nativeQuantity()
                + "|" + value.nativeUnitCode() + "|" + value.tce() + "|" + value.tceUnitCode()
                + "|" + value.coverageRatio() + "|" + value.issueCodes() + "|"
                + calculationHash + "|" + value.exceptionPolicyVersions());
    }

    private PeriodSnapshot snapshot(
            CurrentProjection current, String snapshotId, String resultKey, int version,
            String status, String exceptionVersions, String evidenceJson, String evidenceHash,
            String supersedes, String batchId, long userId, LocalDateTime now) {
        return new PeriodSnapshot(snapshotId, resultKey, current.projectionId(),
                current.buildingId(), current.pointId(), current.periodType(),
                current.startInclusive(), current.endExclusive(), current.timezoneId(),
                current.periodPolicyVersionId(), version, status, current.resultNature(),
                current.energyItemCode(), current.nativeQuantity(), current.nativeUnitCode(),
                current.tce(), current.tceUnitCode(), current.coverageRatio(), current.issueCodes(),
                exceptionVersions, evidenceJson, evidenceHash, current.conversionSelectionJson(),
                current.activityWatermark(), supersedes, batchId, userId, now);
    }

    private NumericResult numeric(CurrentProjection value) {
        return new NumericResult(value.resultKey(), value.buildingId(), value.pointId(),
                value.startInclusive(), value.nativeQuantity(), value.nativeUnitCode(), value.tce(),
                value.tceUnitCode(), value.coverageRatio(), value.resultNature(),
                value.evidenceHash(), value.revision());
    }

    private NumericResult numeric(PeriodSnapshot value, long revision) {
        return new NumericResult(value.resultKey(), value.buildingId(), value.pointId(),
                value.startInclusive(), value.nativeQuantity(), value.nativeUnitCode(), value.tce(),
                value.tceUnitCode(), value.coverageRatio(), value.resultNature(),
                value.evidenceHash(), revision);
    }

    private static ConversionSelection selection(ConversionSelectionRequest value) {
        return value == null ? null : new ConversionSelection(value.method(), value.perspective(),
                value.consumptionScope(), value.regionCode());
    }

    private CurrentProjection requireCurrent(String projectionId) {
        CurrentProjection value = repository.findCurrent(text(projectionId, 32, "投影标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "周期当前投影不存在");
        return value;
    }

    private LockRequest requireLock(String requestId) {
        LockRequest value = repository.findLockRequest(text(requestId, 32, "封账申请标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "封账申请不存在");
        return value;
    }

    private PeriodSnapshot requireSnapshot(String snapshotId) {
        PeriodSnapshot value = repository.findSnapshot(text(snapshotId, 32, "快照标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "周期快照不存在");
        return value;
    }

    private RecalculationBatch requireBatch(String batchId) {
        RecalculationBatch value = repository.findBatch(text(batchId, 32, "重算批次标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "重算批次不存在");
        return value;
    }

    private void advance(String batchId, BatchStatus expected, BatchStatus next) {
        if (repository.advanceBatch(batchId, expected.name(), next.name()) != 1) versionConflict();
    }

    private void audit(long userId, String buildingId, String action, String objectType,
                       String objectId, String versionId, String summary) {
        auditWriter.append(new AuditEvidence("ENERGY_PERIOD", buildingId, "USER", userId,
                action, objectType, objectId, versionId, null, null, summary, "SUCCESS", null,
                TraceContext.current(), LocalDateTime.now(), auditProperties.getEnvironmentMode(), false));
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored energy period evidence is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Energy period evidence serialization failed", exception);
        }
    }

    private static String common(List<String> values, String message) {
        Set<String> distinct = values.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.size() > 1) throw error(409, STATUS_CONFLICT, message);
        return distinct.isEmpty() ? null : distinct.iterator().next();
    }

    private static boolean isTerminal(String status) {
        return SnapshotStatus.SUPERSEDED.name().equals(status)
                || SnapshotStatus.INVALIDATED.name().equals(status);
    }

    private static String safeError(Exception exception) {
        if (exception instanceof BusinessException business && business.getErrorCode() != null) {
            return business.getErrorCode();
        }
        return "ENERGY_RECALCULATION_CALCULATION_FAILED";
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw error(400, VALIDATION_FAILED, message);
        }
    }

    private static String text(String value, int max, String message) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > max) validation(message);
        return normalized;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split(",")).stream().map(String::trim)
                .filter(item -> !item.isBlank()).distinct().toList();
    }

    private static String csv(List<String> values) {
        return values == null ? "" : values.stream().filter(Objects::nonNull).distinct()
                .sorted().collect(Collectors.joining(","));
    }

    private static String digest(String value) {
        return EnergyPeriodCalculationService.digest(value);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void validation(String message) {
        throw error(400, VALIDATION_FAILED, message);
    }

    private static void lockBlocked(String message) {
        throw error(409, LOCK_BLOCKED, message);
    }

    private static void statusConflict(String message) {
        throw error(409, STATUS_CONFLICT, message);
    }

    private static void versionConflict() {
        throw error(409, VERSION_CONFLICT, "状态或版本已被其他操作修改");
    }

    private static ProjectionView view(CurrentProjection value) {
        return new ProjectionView(value.projectionId(), value.resultKey(), value.resultNature(),
                value.buildingId(), value.pointId(), value.periodType(), value.startInclusive(),
                value.endExclusive(), value.timezoneId(), value.periodPolicyVersionId(),
                value.status(), value.revision(), value.energyItemCode(), value.nativeQuantity(),
                value.nativeUnitCode(), value.tce(), value.tceUnitCode(), value.coverageRatio(),
                split(value.issueCodes()), value.evidenceHash(), value.activityWatermark(),
                value.calculatedAt());
    }

    private static LockRequestView view(LockRequest value) {
        return new LockRequestView(value.requestId(), value.projectionId(), value.projectionRevision(),
                value.buildingId(), value.status(), split(value.issuePolicyVersions()), value.reason(),
                value.evidenceReference(), value.submittedBy(), value.submittedAt(), value.reviewedBy(),
                value.reviewedAt(), value.reviewComment());
    }

    private static SnapshotView view(PeriodSnapshot value) {
        return new SnapshotView(value.snapshotId(), value.resultKey(), value.projectionId(),
                value.buildingId(), value.pointId(), value.periodType(), value.startInclusive(),
                value.endExclusive(), value.timezoneId(), value.periodPolicyVersionId(),
                value.snapshotVersion(), value.status(), value.resultNature(), value.energyItemCode(),
                value.nativeQuantity(), value.nativeUnitCode(), value.tce(), value.tceUnitCode(),
                value.coverageRatio(), split(value.issueCodes()),
                split(value.exceptionPolicyVersions()), value.evidenceHash(),
                value.supersedesSnapshotId(), value.sourceBatchId(), value.lockedBy(), value.lockedAt());
    }

    private static RecalculationBatchView view(RecalculationBatch value) {
        return new RecalculationBatchView(value.batchId(), value.buildingId(), value.idempotencyKey(),
                value.mode(), value.status(), value.reason(), value.totalItems(), value.processedItems(),
                value.changedItems(), value.unchangedItems(), value.failedItems(), value.submittedBy(),
                value.submittedAt(), value.approvedBy(), value.approvedAt(), value.reviewComment(),
                value.safeError(), value.completedAt());
    }

    private record PolicyResolution(
            List<String> policyVersionIds, SnapshotStatus status) {
    }
}
