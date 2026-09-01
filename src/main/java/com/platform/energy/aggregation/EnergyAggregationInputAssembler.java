package com.platform.energy.aggregation;

import com.platform.energy.activity.EnergyActivityDataContracts.AggregationActivitySnapshot;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.aggregation.EnergyAggregationModels.*;
import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.energy.catalog.EnergyCatalogLookup.ApprovedEnergyItem;
import com.platform.energy.catalog.EnergyCatalogLookup.ApprovedUnit;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.platform.energy.aggregation.EnergyAggregationErrors.*;

@Component
/** 只通过上游公开服务组合一次可追溯聚合快照，不读取上游内部表。 */
public class EnergyAggregationInputAssembler {
    private static final int RELATION_PAGE_SIZE = 500;

    private final EnergyActivityDataService activityDataService;
    private final EnergyCatalogService catalogService;
    private final EnergyCatalogLookup catalogLookup;
    private final RelationGovernanceService relationService;
    private final EnergyAggregationGovernanceService governanceService;

    public EnergyAggregationInputAssembler(
            EnergyActivityDataService activityDataService,
            EnergyCatalogService catalogService,
            EnergyCatalogLookup catalogLookup,
            RelationGovernanceService relationService,
            EnergyAggregationGovernanceService governanceService) {
        this.activityDataService = activityDataService;
        this.catalogService = catalogService;
        this.catalogLookup = catalogLookup;
        this.relationService = relationService;
        this.governanceService = governanceService;
    }

    public AggregationInput load(
            long userId, Collection<String> roles, AggregationQuery query) {
        validateQuery(query);
        AggregationActivitySnapshot activity = activityDataService.aggregationSnapshot(
                userId, roles, query.buildingId(), query.pointId(),
                query.startInclusive().toEpochMilli(), query.endExclusive().toEpochMilli(),
                query.calculationAsOf().toEpochMilli());
        BindingVersionView binding = catalogService.effectiveBinding(userId, roles,
                query.buildingId(), query.pointId(), local(query.startInclusive()));
        if (!binding.rawUnit().equalsIgnoreCase(activity.sourceUnit())
                || !"CONFIRMED".equals(activity.confirmationStatus())) {
            throw error(INPUT_INCOMPLETE, "活动数据单位或专业确认状态与有效绑定不一致");
        }
        ApprovedEnergyItem item = catalogLookup.findApprovedItem(
                        binding.energyItemCode(), local(query.startInclusive()))
                .orElseThrow(() -> error(INPUT_INCOMPLETE, "能源品种缺少已审核定义"));
        if (!item.versionId().equals(binding.energyItemVersionId())) {
            throw error(INPUT_INCOMPLETE, "测点绑定引用的能源品种版本已失效");
        }
        ApprovedUnit unit = catalogLookup.findApprovedUnit(binding.rawUnit(), local(query.startInclusive()))
                .orElseThrow(() -> error(INPUT_INCOMPLETE, "原始单位缺少已审核定义"));
        requireCoverage(binding.effectiveTo(), item.effectiveTo(), unit.effectiveTo(), query.endExclusive());

        ValueSemantics semantics;
        try {
            semantics = ValueSemantics.valueOf(activity.valueSemantics());
        } catch (IllegalArgumentException exception) {
            throw error(INPUT_INCOMPLETE, "测点活动语义无效");
        }
        if (semantics == ValueSemantics.PERIOD_TOTAL) {
            throw error(PERIOD_COVERAGE_INVALID, "当前原始活动契约尚未提供显式源周期边界");
        }
        MeasurementContext measurement = new MeasurementContext(binding.energyItemCode(),
                binding.bindingVersionId(), binding.rawUnit(), unit.versionId(), semantics,
                unit.standardConditionCode(), DataNature.SIMULATED, binding.confirmationStatus(),
                instant(binding.effectiveFrom()), instant(binding.effectiveTo()),
                binding.evidenceReference());

        AssignmentSnapshot assignment = assignment(userId, roles, query.buildingId(), query.pointId());
        List<ActivityFact> facts = activity.items().stream().map(this::fact).toList();
        Set<String> factIdentities = new HashSet<>();
        facts.forEach(value -> factIdentities.add(value.factIdentity()));
        List<CorrectionEvidence> corrections = governanceService
                .approvedCorrections(query.buildingId(), query.pointId()).stream()
                .filter(value -> factIdentities.contains(value.originalFactIdentity())).toList();
        Instant eventFrom = facts.stream().map(ActivityFact::eventTime).min(Instant::compareTo)
                .orElse(query.startInclusive());
        List<MeterEventEvidence> events = governanceService.approvedEvents(query.buildingId(),
                query.pointId(), eventFrom, query.endExclusive());
        IntegrationPolicy policy = semantics == ValueSemantics.INSTANTANEOUS
                ? governanceService.effectivePolicy(query.buildingId(), query.pointId(),
                query.startInclusive()) : null;
        return new AggregationInput(query, measurement, assignment.evidence(),
                Instant.ofEpochMilli(activity.activityWatermark()), facts, events, corrections, policy);
    }

    private AssignmentSnapshot assignment(
            long userId, Collection<String> roles, String buildingId, String pointId) {
        List<MeteringAssignmentView> matched = new ArrayList<>();
        MeteringAssignmentsView first = null;
        int page = 1;
        do {
            MeteringAssignmentsView current = relationService.effectiveMeteringAssignments(
                    userId, roles, buildingId, page, RELATION_PAGE_SIZE);
            if (first == null) first = current;
            current.items().stream().filter(value -> pointId.equals(value.pointId())).forEach(matched::add);
            if (page * (long) current.size() >= current.total()) break;
            page++;
        } while (page <= 10_000);
        if (first == null || matched.size() != 1) {
            throw error(INPUT_INCOMPLETE, "测点必须且只能存在一条有效计量分配");
        }
        MeteringAssignmentView value = matched.getFirst();
        String confirmation = "ASSIGNED".equals(value.allocationStatus())
                && "ACTIVE".equals(value.boundaryStatus())
                && "CONFIRMED".equals(value.boundaryConfirmationStatus())
                && "CONFIRMED".equals(value.meterConfirmationStatus())
                && !"UNKNOWN".equals(value.meterRole())
                && Set.of("INBOUND", "OUTBOUND").contains(value.meterDirection())
                ? "CONFIRMED" : "PENDING_EXPERT";
        return new AssignmentSnapshot(new MeteringAssignmentEvidence(first.metadata().versionId(),
                first.metadata().modelRevision(), value.assignmentItemId(), value.meteringBoundaryId(),
                value.targetNodeId(), value.allocationStatus(), confirmation));
    }

    private ActivityFact fact(RawActivityDataView value) {
        return new ActivityFact(value.pointId() + "@" + value.eventTime(),
                BigDecimal.valueOf(value.rawValue()), Instant.ofEpochMilli(value.eventTime()),
                Instant.ofEpochMilli(value.receivedTime()), value.qualityLevel(),
                value.policySource() + ":" + value.policyVersion() + ":" + value.policyConfigRevision(),
                value.late(), null, null, null, null);
    }

    private static void validateQuery(AggregationQuery query) {
        if (query == null || query.buildingId() == null || query.buildingId().isBlank()
                || query.pointId() == null || query.pointId().isBlank()
                || query.startInclusive() == null || query.endExclusive() == null
                || query.calculationAsOf() == null
                || !query.startInclusive().isBefore(query.endExclusive())
                || query.calculationAsOf().isBefore(query.endExclusive())) {
            throw error(400, VALIDATION_FAILED, "聚合对象或时间范围无效");
        }
    }

    private static void requireCoverage(
            LocalDateTime bindingEnd, LocalDateTime itemEnd,
            LocalDateTime unitEnd, Instant requestedEnd) {
        LocalDateTime end = local(requestedEnd);
        if (bindingEnd != null && bindingEnd.isBefore(end)
                || itemEnd != null && itemEnd.isBefore(end)
                || unitEnd != null && unitEnd.isBefore(end)) {
            throw error(INPUT_INCOMPLETE, "绑定或单位版本不能覆盖完整聚合周期");
        }
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record AssignmentSnapshot(MeteringAssignmentEvidence evidence) {
    }
}
