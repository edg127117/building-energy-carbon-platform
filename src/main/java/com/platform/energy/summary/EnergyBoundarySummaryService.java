package com.platform.energy.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.summary.EnergySummaryModels.AssignmentEvidence;
import com.platform.energy.summary.EnergySummaryModels.BoundaryPolicyVersion;
import com.platform.energy.summary.EnergySummaryModels.QueryDimension;
import com.platform.energy.summary.EnergySummaryModels.QueryGroup;
import com.platform.energy.summary.EnergySummaryModels.SnapshotMeasure;
import com.platform.energy.summary.api.EnergySummaryContracts.ApproveBoundaryPolicyRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.BoundaryPolicyView;
import com.platform.energy.summary.api.EnergySummaryContracts.CreateBoundaryPolicyRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryGroupView;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryView;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.platform.energy.summary.EnergySummaryErrors.*;
import static com.platform.energy.summary.EnergySummaryModels.AggregationMode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 治理研发汇总口径，并基于封账快照和固定关系版本提供安全多维查询。 */
public class EnergyBoundarySummaryService {
    private static final int PAGE_SIZE = 500;
    private static final int MAX_SNAPSHOTS = 500;
    private static final int MAX_RELATION_ROWS = 5_000;
    private static final Set<String> PERIOD_TYPES = Set.of("DAY", "MONTH", "YEAR");

    private final EnergyPeriodAuthorization authorization;
    private final EnergyBoundarySummaryRepository repository;
    private final EnergyPeriodSnapshotReader snapshotReader;
    private final RelationGovernanceService relationService;
    private final EnergyCatalogLookup catalogLookup;
    private final EnergyBoundarySummaryCore core;
    private final ObjectMapper objectMapper;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    @Transactional(rollbackFor = Exception.class)
    public BoundaryPolicyView createPolicy(
            long userId, Collection<String> roles, CreateBoundaryPolicyRequest request) {
        authorization.requirePolicyMaintainer(userId, roles);
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.checkBuilding(userId, roles, buildingId);
        String boundaryId = text(request.meteringBoundaryId(), 32, "计量边界标识无效");
        String itemCode = code(request.energyItemCode(), "能源品种编码无效");
        aggregationMode(request.aggregationMode());
        if (!"SIMULATION".equals(normalize(request.sourceType()))) {
            validation("研发汇总口径来源必须标记为SIMULATION");
        }
        if (catalogLookup.findApprovedItem(itemCode, request.effectiveFrom()).isEmpty()) {
            throw error(409, POLICY_REQUIRED, "能源品种尚无已审核版本");
        }
        if (!confirmedBoundary(userId, roles, buildingId, boundaryId)) {
            throw error(409, RELATION_UNCONFIRMED, "计量边界不存在或尚未确认");
        }
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        LocalDateTime now = LocalDateTime.now();
        String policyId = repository.findPolicyIdForUpdate(buildingId, boundaryId, itemCode);
        if (policyId == null) {
            policyId = id();
            try {
                repository.insertIdentity(policyId, buildingId, boundaryId, itemCode, userId, now);
            } catch (DuplicateKeyException exception) {
                versionConflict();
            }
        }
        BoundaryPolicyVersion value = new BoundaryPolicyVersion(policyId, id(),
                repository.nextVersion(policyId), buildingId, boundaryId, itemCode,
                normalize(request.aggregationMode()), "PENDING_EXPERT", "SIMULATION",
                text(request.evidenceReference(), 500, "证据引用无效"), range.from(), range.to(),
                0, userId, now, null, null, null);
        repository.insertVersion(value);
        audit(userId, buildingId, "CREATE_ENERGY_BOUNDARY_SUMMARY_POLICY",
                value.policyId(), value.versionId(), null, summary(value), false);
        return view(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public BoundaryPolicyView approvePolicy(
            long userId, Collection<String> roles, String versionId,
            ApproveBoundaryPolicyRequest request) {
        authorization.requirePolicyReviewer(userId, roles);
        BoundaryPolicyVersion value = requirePolicy(versionId);
        authorization.checkBuilding(userId, roles, value.buildingId());
        authorization.requireSeparation(value.createdBy(), userId);
        if (!"PENDING_EXPERT".equals(value.status())) versionConflict();
        BoundaryPolicyVersion previous = repository.findApproved(value.buildingId(),
                value.meteringBoundaryId(), value.energyItemCode(), value.effectiveFrom());
        if (previous != null) {
            if (!value.effectiveFrom().isAfter(previous.effectiveFrom())) {
                validation("新审核版本必须晚于当前已审核版本生效");
            }
            repository.closeApproved(value.policyId(), value.versionId(), value.effectiveFrom());
        }
        LocalDateTime now = LocalDateTime.now();
        if (repository.approve(value.versionId(), request.expectedRevision(), userId, now,
                text(request.reviewComment(), 500, "审核意见无效")) != 1) {
            versionConflict();
        }
        BoundaryPolicyVersion approved = requirePolicy(versionId);
        audit(userId, approved.buildingId(), "APPROVE_ENERGY_BOUNDARY_SUMMARY_POLICY",
                approved.policyId(), approved.versionId(), summary(value), summary(approved),
                value.createdBy() == userId && auditProperties.isAllowSelfApproval());
        return view(approved);
    }

    public SummaryQueryView query(
            long userId, Collection<String> roles, SummaryQueryRequest request) {
        authorization.requireReader(roles);
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.checkBuilding(userId, roles, buildingId);
        String periodType = normalize(request.periodType());
        if (!PERIOD_TYPES.contains(periodType)) validation("周期类型无效");
        if (request.startInclusive() == null || request.endExclusive() == null
                || !request.endExclusive().isAfter(request.startInclusive())) {
            validation("查询周期边界无效");
        }
        List<QueryDimension> dimensions = dimensions(request.dimensions());
        List<PeriodSnapshot> snapshots = snapshotReader.listVisibleSnapshots(buildingId,
                periodType, request.startInclusive(), request.endExclusive(), MAX_SNAPSHOTS + 1);
        if (snapshots.size() > MAX_SNAPSHOTS) {
            throw error(409, RESULT_LIMIT_EXCEEDED, "查询封账快照超过500条，请缩小周期范围");
        }
        Map<String, RelationSnapshot> relations = relations(userId, roles, buildingId, snapshots);
        List<SnapshotMeasure> measures = snapshots.stream().map(value -> measure(value,
                relations.get(relationVersion(value)))).toList();
        List<AssignmentEvidence> assignments = relations.values().stream()
                .flatMap(value -> value.assignments().stream()).toList();
        List<BoundaryPolicyVersion> policies = policies(measures, assignments);
        List<QueryGroup> groups = core.project(core.summarize(measures, assignments, policies), dimensions);
        return new SummaryQueryView(buildingId, periodType, request.startInclusive(),
                request.endExclusive(), dimensions.stream().map(Enum::name).toList(),
                snapshots.size(), groups.stream().map(EnergyBoundarySummaryService::view).toList());
    }

    private Map<String, RelationSnapshot> relations(
            long userId, Collection<String> roles, String buildingId,
            List<PeriodSnapshot> snapshots) {
        Set<String> versions = snapshots.stream().map(this::relationVersion)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        Map<String, RelationSnapshot> result = new LinkedHashMap<>();
        for (String versionId : versions) {
            List<AssignmentEvidence> rows = new ArrayList<>();
            int page = 1;
            MeteringAssignmentsView first = null;
            while (true) {
                MeteringAssignmentsView current = relationService.historicalMeteringAssignments(
                        userId, roles, buildingId, versionId, page, PAGE_SIZE);
                if (first == null) first = current;
                long revision = current.metadata().modelRevision();
                current.items().stream().map(value -> assignment(versionId, revision, value))
                        .forEach(rows::add);
                if (rows.size() > MAX_RELATION_ROWS) {
                    throw error(409, RESULT_LIMIT_EXCEEDED,
                            "关系版本计量分配超过5000条，拒绝无界汇总");
                }
                if (page * (long) current.size() >= current.total()) break;
                page++;
            }
            if (first == null) relationUnconfirmed("历史关系版本不存在");
            result.put(versionId, new RelationSnapshot(first.metadata().modelRevision(), rows));
        }
        return Map.copyOf(result);
    }

    private List<BoundaryPolicyVersion> policies(
            List<SnapshotMeasure> measures, List<AssignmentEvidence> assignments) {
        Map<MeasureKey, List<AssignmentEvidence>> index = assignments.stream()
                .filter(value -> value.pointId() != null)
                .collect(java.util.stream.Collectors.groupingBy(value ->
                        new MeasureKey(value.relationVersionId(), value.pointId())));
        Map<String, BoundaryPolicyVersion> result = new LinkedHashMap<>();
        for (SnapshotMeasure measure : measures) {
            List<AssignmentEvidence> matched = index.getOrDefault(
                    new MeasureKey(measure.relationVersionId(), measure.pointId()), List.of());
            Set<String> boundaries = matched.stream().map(AssignmentEvidence::meteringBoundaryId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            if (boundaries.size() != 1) relationUnconfirmed("测点未唯一归属一个计量边界");
            String boundaryId = boundaries.iterator().next();
            LocalDateTime at = LocalDateTime.ofInstant(measure.startInclusive(),
                    ZoneId.of(measure.timezoneId()));
            BoundaryPolicyVersion policy = repository.findApproved(measure.buildingId(),
                    boundaryId, measure.energyItemCode(), at);
            if (policy != null) result.put(policy.versionId(), policy);
        }
        return List.copyOf(result.values());
    }

    private SnapshotMeasure measure(PeriodSnapshot value, RelationSnapshot relation) {
        if (relation == null) relationUnconfirmed("周期结果引用的关系版本不存在");
        JsonNode evidence = json(value.evidenceJson());
        JsonNode selection = value.conversionSelectionJson() == null
                ? objectMapper.createObjectNode() : json(value.conversionSelectionJson());
        String formula = textOrNull(evidence.path("formulaVersionId"));
        String parameter = textOrNull(evidence.path("parameterVersionId"));
        String conversionRule = join(formula, parameter);
        return new SnapshotMeasure(value.snapshotId(), value.buildingId(), value.pointId(),
                value.periodType(), value.startInclusive(), value.endExclusive(), value.timezoneId(),
                value.status(), value.resultNature(), value.energyItemCode(), value.nativeQuantity(),
                value.nativeUnitCode(), value.tce(), value.tceUnitCode(), value.coverageRatio(),
                split(value.issueCodes()), value.evidenceHash(), relationVersion(value),
                relation.modelRevision(), defaultText(selection.path("consumptionScope"), "UNSPECIFIED"),
                textOrNull(selection.path("perspective")), conversionRule);
    }

    private static AssignmentEvidence assignment(
            String versionId, long revision, MeteringAssignmentView value) {
        return new AssignmentEvidence(versionId, revision, value.pointId(), value.allocationStatus(),
                value.meteringBoundaryId(), value.meteringBoundaryCode(),
                value.meteringBoundaryName(), value.boundaryConfirmationStatus(),
                value.boundaryStatus(), value.meterRole(), value.meterDirection(),
                value.meterConfirmationStatus(), targetType(value.targetNodeType()), value.targetObjectId(),
                value.targetObjectCode(), value.targetObjectName());
    }

    private boolean confirmedBoundary(
            long userId, Collection<String> roles, String buildingId, String boundaryId) {
        int page = 1;
        while (true) {
            var current = relationService.effectiveBoundaries(
                    userId, roles, buildingId, page, PAGE_SIZE);
            if (current.items().stream().anyMatch(value -> boundaryId.equals(value.boundaryId())
                    && "ACTIVE".equals(value.status())
                    && "CONFIRMED".equals(value.confirmationStatus()))) {
                return true;
            }
            if (page * (long) current.size() >= current.total()) return false;
            page++;
        }
    }

    private static List<QueryDimension> dimensions(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 5) validation("查询维度必须为1到5项");
        List<QueryDimension> result = values.stream().map(value -> {
            try {
                return QueryDimension.valueOf(normalize(value));
            } catch (RuntimeException exception) {
                validation("查询维度无效");
                return null;
            }
        }).toList();
        if (new LinkedHashSet<>(result).size() != result.size()) validation("查询维度不得重复");
        return result;
    }

    private String relationVersion(PeriodSnapshot value) {
        try {
            JsonNode root = objectMapper.readTree(value.evidenceJson());
            String version = textOrNull(root.path("relationVersionId"));
            if (version == null) relationUnconfirmed("周期结果缺少关系版本证据");
            return version;
        } catch (Exception exception) {
            relationUnconfirmed("周期结果关系版本证据无效");
            return null;
        }
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            validation("周期结果证据格式无效");
            return null;
        }
    }

    private BoundaryPolicyVersion requirePolicy(String versionId) {
        BoundaryPolicyVersion value = repository.findVersion(text(versionId, 32, "版本标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "计量边界汇总口径版本不存在");
        return value;
    }

    private void audit(
            long userId, String buildingId, String action, String policyId,
            String versionId, String before, String after, boolean selfApproval) {
        auditWriter.append(new AuditEvidence("ENERGY_SUMMARY", buildingId, "USER", userId,
                action, "ENERGY_BOUNDARY_SUMMARY_POLICY", policyId, versionId, null,
                before, after, "SUCCESS", null, TraceContext.current(), LocalDateTime.now(),
                auditProperties.getEnvironmentMode(), selfApproval));
    }

    private static BoundaryPolicyView view(BoundaryPolicyVersion value) {
        return new BoundaryPolicyView(value.policyId(), value.versionId(), value.versionNo(),
                value.buildingId(), value.meteringBoundaryId(), value.energyItemCode(),
                value.aggregationMode(), value.status(), value.sourceType(),
                value.evidenceReference(), value.effectiveFrom(), value.effectiveTo(),
                value.configRevision(), value.createdBy(), value.createdAt(), value.approvedBy(),
                value.approvedAt(), value.reviewComment());
    }

    private static SummaryGroupView view(QueryGroup value) {
        return new SummaryGroupView(value.groupKey(), value.originalQuantities(),
                value.grossInboundQuantities(), value.grossOutboundQuantities(),
                value.netQuantities(), value.tceByPerspective(), value.assignedQuantities(),
                value.unallocatedQuantities(), value.residualQuantities(), value.coverageRatio(),
                value.exceptionCount(), value.lockStatus(), value.resultCompleteness(),
                value.relationVersionIds(), value.conversionRuleVersionIds(),
                value.summaryPolicyVersionIds(), value.evidenceHashes(), value.resultNature());
    }

    private static String summary(BoundaryPolicyVersion value) {
        return "version=" + value.versionNo() + ";boundary=" + value.meteringBoundaryId()
                + ";item=" + value.energyItemCode() + ";mode=" + value.aggregationMode()
                + ";status=" + value.status();
    }

    private static AggregationMode aggregationMode(String value) {
        try {
            return AggregationMode.valueOf(normalize(value));
        } catch (RuntimeException exception) {
            validation("计量边界汇总口径无效");
            return null;
        }
    }

    private static TimeRange range(LocalDateTime from, LocalDateTime to) {
        if (from == null || (to != null && !to.isAfter(from))) validation("业务有效期无效");
        return new TimeRange(from, to);
    }

    private static String code(String value, String message) {
        String result = normalize(value);
        if (result == null || !result.matches("[A-Z][A-Z0-9_]{1,63}")) validation(message);
        return result;
    }

    private static String text(String value, int max, String message) {
        if (value == null || value.isBlank() || value.trim().length() > max) validation(message);
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).toList();
    }

    private static String textOrNull(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || value.asText().isBlank() ? null : value.asText();
    }

    private static String defaultText(JsonNode value, String fallback) {
        String result = textOrNull(value);
        return result == null ? fallback : result;
    }

    private static String targetType(String value) {
        return "EQUIPMENT".equals(value) ? "DEVICE" : value;
    }

    private static String join(String left, String right) {
        if (left == null) return right;
        if (right == null) return left;
        return left + "+" + right;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void validation(String message) {
        throw error(400, VALIDATION_FAILED, message);
    }

    private static void relationUnconfirmed(String message) {
        throw error(409, RELATION_UNCONFIRMED, message);
    }

    private static void versionConflict() {
        throw error(409, VERSION_CONFLICT, "状态或版本已被其他操作修改");
    }

    private record TimeRange(LocalDateTime from, LocalDateTime to) {
    }

    private record RelationSnapshot(long modelRevision, List<AssignmentEvidence> assignments) {
    }

    private record MeasureKey(String relationVersionId, String pointId) {
    }
}
