package com.platform.energy.summary;

import com.platform.energy.summary.EnergySummaryModels.AssignmentEvidence;
import com.platform.energy.summary.EnergySummaryModels.BoundaryAggregate;
import com.platform.energy.summary.EnergySummaryModels.BoundaryPolicyVersion;
import com.platform.energy.summary.EnergySummaryModels.QueryDimension;
import com.platform.energy.summary.EnergySummaryModels.QueryGroup;
import com.platform.energy.summary.EnergySummaryModels.SnapshotMeasure;
import com.platform.energy.summary.EnergySummaryModels.TargetContribution;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.platform.energy.summary.EnergySummaryErrors.POLICY_REQUIRED;
import static com.platform.energy.summary.EnergySummaryErrors.RELATION_UNCONFIRMED;
import static com.platform.energy.summary.EnergySummaryErrors.error;
import static com.platform.energy.summary.EnergySummaryModels.AggregationMode;

@Component
/** 执行总分表去重、显式未分配和受限多维投影，不读取任何上游内部表。 */
public class EnergyBoundarySummaryCore {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Set<String> TARGET_TYPES = Set.of("SPACE", "SYSTEM", "DEVICE");

    public List<BoundaryAggregate> summarize(
            List<SnapshotMeasure> measures, List<AssignmentEvidence> assignments,
            List<BoundaryPolicyVersion> policies) {
        Map<MeasureKey, List<AssignmentEvidence>> assignmentIndex = assignments.stream()
                .filter(value -> value.pointId() != null)
                .collect(Collectors.groupingBy(value -> new MeasureKey(
                        value.relationVersionId(), value.pointId())));
        List<MeterDatum> data = measures.stream()
                .map(value -> datum(value, assignmentIndex.getOrDefault(
                        new MeasureKey(value.relationVersionId(), value.pointId()), List.of())))
                .toList();
        Map<BoundaryKey, List<MeterDatum>> groups = data.stream()
                .collect(Collectors.groupingBy(MeterDatum::boundaryKey,
                        LinkedHashMap::new, Collectors.toList()));
        List<BoundaryAggregate> result = new ArrayList<>();
        groups.forEach((key, values) -> result.add(aggregate(key, values,
                policy(key, values.getFirst().measure(), policies))));
        result.sort(Comparator.comparing(BoundaryAggregate::startInclusive)
                .thenComparing(BoundaryAggregate::meteringBoundaryId)
                .thenComparing(BoundaryAggregate::energyItemCode)
                .thenComparing(BoundaryAggregate::flowDirection));
        return List.copyOf(result);
    }

    public List<QueryGroup> project(
            List<BoundaryAggregate> aggregates, List<QueryDimension> dimensions) {
        boolean targetGrouping = dimensions.stream().anyMatch(this::targetDimension);
        List<ProjectedValue> values = new ArrayList<>();
        for (BoundaryAggregate aggregate : aggregates) {
            if (!targetGrouping) {
                values.add(authority(aggregate, dimensions));
                continue;
            }
            for (TargetContribution contribution : aggregate.targetContributions()) {
                values.add(target(aggregate, contribution, dimensions));
            }
        }
        Map<Map<String, String>, List<ProjectedValue>> grouped = values.stream()
                .collect(Collectors.groupingBy(ProjectedValue::key,
                        LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().map(entry -> group(entry.getKey(), entry.getValue()))
                .toList();
    }

    private MeterDatum datum(SnapshotMeasure measure, List<AssignmentEvidence> matched) {
        if (matched.isEmpty()) relation("周期结果缺少固定关系版本中的计量分配");
        Set<String> boundaries = matched.stream().map(AssignmentEvidence::meteringBoundaryId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (boundaries.size() != 1) relation("测点未唯一归属一个计量边界");
        AssignmentEvidence primary = matched.getFirst();
        boolean sameStructure = matched.stream().allMatch(value ->
                Objects.equals(primary.meterRole(), value.meterRole())
                        && Objects.equals(primary.meterDirection(), value.meterDirection())
                        && Objects.equals(primary.meterConfirmationStatus(),
                        value.meterConfirmationStatus()));
        if (!sameStructure || !"ACTIVE".equals(primary.boundaryStatus())
                || !"CONFIRMED".equals(primary.boundaryConfirmationStatus())
                || !"CONFIRMED".equals(primary.meterConfirmationStatus())
                || matched.stream().anyMatch(value -> Set.of("PENDING_EXPERT", "INVALID")
                .contains(value.allocationStatus()))
                || "UNKNOWN".equals(primary.meterRole())
                || !Set.of("INBOUND", "OUTBOUND").contains(primary.meterDirection())) {
            relation("计量边界、表计角色或流向尚未确认");
        }
        List<AssignmentEvidence> targets = matched.stream()
                .filter(value -> "ASSIGNED".equals(value.allocationStatus()))
                .filter(value -> value.targetObjectId() != null)
                .filter(value -> TARGET_TYPES.contains(value.targetNodeType()))
                .distinct().toList();
        AssignmentEvidence target = targets.size() == 1 ? targets.getFirst() : null;
        return new MeterDatum(measure, primary, target);
    }

    private BoundaryPolicyVersion policy(
            BoundaryKey key, SnapshotMeasure measure, List<BoundaryPolicyVersion> policies) {
        LocalDateTime at = LocalDateTime.ofInstant(measure.startInclusive(),
                ZoneId.of(measure.timezoneId()));
        List<BoundaryPolicyVersion> matched = policies.stream()
                .filter(value -> value.buildingId().equals(key.buildingId()))
                .filter(value -> value.meteringBoundaryId().equals(key.boundaryId()))
                .filter(value -> value.energyItemCode().equals(key.energyItemCode()))
                .filter(value -> "APPROVED".equals(value.status()))
                .filter(value -> !value.effectiveFrom().isAfter(at))
                .filter(value -> value.effectiveTo() == null || value.effectiveTo().isAfter(at))
                .toList();
        if (matched.size() != 1) {
            throw error(409, POLICY_REQUIRED, "计量边界汇总口径缺失或冲突");
        }
        return matched.getFirst();
    }

    private BoundaryAggregate aggregate(
            BoundaryKey key, List<MeterDatum> values, BoundaryPolicyVersion policy) {
        AggregationMode mode;
        try {
            mode = AggregationMode.valueOf(policy.aggregationMode());
        } catch (RuntimeException exception) {
            throw error(409, POLICY_REQUIRED, "计量边界汇总口径无效");
        }
        List<MeterDatum> selected = selected(mode, values);
        BigDecimal authority = sum(selected, datum -> datum.measure().nativeQuantity());
        BigDecimal authorityTce = completeSum(selected, datum -> datum.measure().tce());
        List<MeterDatum> breakdown = mode == AggregationMode.MAIN_WITH_SUBMETER_BREAKDOWN
                ? role(values, "SUB") : selected;
        BigDecimal breakdownQuantity = sum(breakdown,
                datum -> datum.measure().nativeQuantity());
        BigDecimal residual = mode == AggregationMode.MAIN_WITH_SUBMETER_BREAKDOWN
                ? authority.subtract(breakdownQuantity, MC) : BigDecimal.ZERO;
        if (residual.signum() < 0) relation("分表合计超过总表，无法形成正式边界总量");

        List<TargetContribution> contributions = new ArrayList<>();
        for (MeterDatum datum : breakdown) {
            AssignmentEvidence target = datum.target();
            String allocation = target == null ? "UNALLOCATED" : "ASSIGNED";
            contributions.add(new TargetContribution(target == null ? null : target.targetNodeType(),
                    target == null ? null : target.targetObjectId(),
                    target == null ? null : target.targetObjectCode(),
                    target == null ? null : target.targetObjectName(), allocation,
                    datum.measure().nativeQuantity(), datum.measure().tce(), false));
        }
        if (residual.signum() > 0) {
            BigDecimal breakdownTce = completeSum(breakdown, datum -> datum.measure().tce());
            BigDecimal residualTce = authorityTce == null || breakdownTce == null
                    ? null : authorityTce.subtract(breakdownTce, MC);
            contributions.add(new TargetContribution(null, null, null, null,
                    "UNATTRIBUTED_RESIDUAL", residual, residualTce, true));
        }
        BigDecimal assigned = contributions.stream()
                .filter(value -> "ASSIGNED".equals(value.allocationStatus()))
                .map(TargetContribution::quantity).reduce(BigDecimal.ZERO,
                        (left, right) -> left.add(right, MC));
        BigDecimal unallocated = authority.subtract(assigned, MC);
        BigDecimal coverage = selected.stream().map(value -> value.measure().coverageRatio())
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        int exceptions = selected.stream().mapToInt(value -> value.measure().issueCodes().size()).sum();
        String completeness = selected.stream().anyMatch(value ->
                "LOCKED_PARTIAL".equals(value.measure().lockStatus())) ? "INCOMPLETE"
                : exceptions > 0 ? "COMPLETE_WITH_ALLOWED_QUALITY" : "COMPLETE";
        String nature = selected.stream().allMatch(value ->
                "FORMAL".equals(value.measure().resultNature()))
                && !"SIMULATION".equals(policy.sourceType()) ? "FORMAL"
                : "DEVELOPMENT_SIMULATION";
        String lock = common(selected, value -> value.measure().lockStatus(), "MIXED");
        String rule = common(selected, value -> value.measure().conversionRuleVersionId(), null);
        return new BoundaryAggregate(key.buildingId(), key.boundaryId(),
                values.getFirst().assignment().meteringBoundaryCode(),
                values.getFirst().assignment().meteringBoundaryName(), key.energyItemCode(),
                key.energySource(), key.flowDirection(), key.periodType(), key.startInclusive(),
                key.endExclusive(), key.nativeUnitCode(), authority, assigned, unallocated,
                residual, key.tceUnitCode(), authorityTce, coverage, exceptions, lock,
                completeness, nature, key.relationVersionId(), key.relationModelRevision(),
                key.conversionPerspective(), rule, policy.versionId(), mode.name(),
                selected.stream().map(value -> value.measure().evidenceHash()).distinct().toList(),
                contributions);
    }

    private List<MeterDatum> selected(AggregationMode mode, List<MeterDatum> values) {
        return switch (mode) {
            case MAIN_METER_TOTAL, MAIN_WITH_SUBMETER_BREAKDOWN -> {
                List<MeterDatum> mains = role(values, "MAIN");
                if (mains.size() != 1) relation("总表口径必须且只能匹配一只总表");
                yield mains;
            }
            case SUBMETER_SUM -> required(role(values, "SUB"), "分表求和口径缺少分表");
            case INDEPENDENT_METER_SUM -> required(role(values, "INDEPENDENT"),
                    "独立表求和口径缺少独立表");
        };
    }

    private static List<MeterDatum> role(List<MeterDatum> values, String role) {
        return values.stream().filter(value -> role.equals(value.assignment().meterRole())).toList();
    }

    private static List<MeterDatum> required(List<MeterDatum> values, String message) {
        if (values.isEmpty()) relation(message);
        return values;
    }

    private ProjectedValue authority(BoundaryAggregate value, List<QueryDimension> dimensions) {
        return new ProjectedValue(key(value, null, dimensions), value.nativeUnitCode(),
                value.authorityQuantity(), value.assignedQuantity(), value.unallocatedQuantity(),
                value.residualQuantity(), value.tceUnitCode(), value.authorityTce(),
                value.flowDirection(), value.coverageRatio(), value.exceptionCount(),
                value.lockStatus(), value.resultCompleteness(), value.relationVersionId(),
                value.conversionRuleVersionId(), value.summaryPolicyVersionId(),
                value.evidenceHashes(), value.resultNature(), value.conversionPerspective());
    }

    private ProjectedValue target(
            BoundaryAggregate value, TargetContribution contribution,
            List<QueryDimension> dimensions) {
        boolean assigned = "ASSIGNED".equals(contribution.allocationStatus());
        return new ProjectedValue(key(value, contribution, dimensions), value.nativeUnitCode(),
                contribution.quantity(), assigned ? contribution.quantity() : BigDecimal.ZERO,
                assigned ? BigDecimal.ZERO : contribution.quantity(),
                contribution.residual() ? contribution.quantity() : BigDecimal.ZERO,
                value.tceUnitCode(), contribution.tce(), value.flowDirection(),
                value.coverageRatio(), value.exceptionCount(), value.lockStatus(),
                value.resultCompleteness(), value.relationVersionId(),
                value.conversionRuleVersionId(), value.summaryPolicyVersionId(),
                value.evidenceHashes(), value.resultNature(), value.conversionPerspective());
    }

    private Map<String, String> key(
            BoundaryAggregate value, TargetContribution target,
            List<QueryDimension> dimensions) {
        Map<String, String> key = new LinkedHashMap<>();
        for (QueryDimension dimension : dimensions) {
            key.put(dimension.name(), switch (dimension) {
                case BUILDING -> value.buildingId();
                case METERING_BOUNDARY -> value.meteringBoundaryId();
                case SPACE -> targetValue(target, "SPACE");
                case SYSTEM -> targetValue(target, "SYSTEM");
                case DEVICE -> targetValue(target, "DEVICE");
                case ENERGY_ITEM -> value.energyItemCode();
                case ENERGY_SOURCE -> value.energySource();
                case FLOW_DIRECTION -> value.flowDirection();
                case PERIOD -> value.startInclusive() + "/" + value.endExclusive();
            });
        }
        return Map.copyOf(key);
    }

    private static String targetValue(TargetContribution target, String type) {
        if (target == null || !type.equals(target.targetNodeType())) return "UNALLOCATED";
        return target.targetObjectId();
    }

    private QueryGroup group(Map<String, String> key, List<ProjectedValue> values) {
        Map<String, BigDecimal> original = sumBy(values, ProjectedValue::quantity);
        Map<String, BigDecimal> inbound = sumBy(values.stream()
                .filter(value -> "INBOUND".equals(value.flowDirection())).toList(),
                ProjectedValue::quantity);
        Map<String, BigDecimal> outbound = sumBy(values.stream()
                .filter(value -> "OUTBOUND".equals(value.flowDirection())).toList(),
                ProjectedValue::quantity);
        Map<String, BigDecimal> net = subtract(inbound, outbound);
        Map<String, BigDecimal> tce = values.stream().filter(value -> value.tce() != null)
                .collect(Collectors.groupingBy(value -> value.perspective() == null
                                ? "UNSPECIFIED" : value.perspective(), LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO, ProjectedValue::signedTce,
                                (left, right) -> left.add(right, MC))));
        return new QueryGroup(key, original, inbound, outbound, net,
                Map.copyOf(tce), sumBy(values, ProjectedValue::assigned),
                sumBy(values, ProjectedValue::unallocated),
                sumBy(values, ProjectedValue::residual),
                values.stream().map(ProjectedValue::coverage).min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO),
                values.stream().mapToInt(ProjectedValue::exceptions).sum(),
                common(values, ProjectedValue::lockStatus, "MIXED"),
                worst(values.stream().map(ProjectedValue::completeness).toList()),
                distinct(values, ProjectedValue::relationVersion),
                distinct(values, ProjectedValue::conversionRuleVersion),
                distinct(values, ProjectedValue::summaryPolicyVersion),
                values.stream().flatMap(value -> value.evidenceHashes().stream()).distinct().toList(),
                values.stream().allMatch(value -> "FORMAL".equals(value.resultNature()))
                        ? "FORMAL" : "DEVELOPMENT_SIMULATION");
    }

    private static Map<String, BigDecimal> sumBy(
            List<ProjectedValue> values, Function<ProjectedValue, BigDecimal> extractor) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (ProjectedValue value : values) {
            result.merge(value.unit(), extractor.apply(value),
                    (left, right) -> left.add(right, MC));
        }
        return Map.copyOf(result);
    }

    private static Map<String, BigDecimal> subtract(
            Map<String, BigDecimal> inbound, Map<String, BigDecimal> outbound) {
        Set<String> units = new LinkedHashSet<>(inbound.keySet());
        units.addAll(outbound.keySet());
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        units.forEach(unit -> result.put(unit, inbound.getOrDefault(unit, BigDecimal.ZERO)
                .subtract(outbound.getOrDefault(unit, BigDecimal.ZERO), MC)));
        return Map.copyOf(result);
    }

    private static String worst(List<String> values) {
        if (values.contains("BLOCKED")) return "BLOCKED";
        if (values.contains("INCOMPLETE")) return "INCOMPLETE";
        if (values.contains("COMPLETE_WITH_ALLOWED_QUALITY")) return "COMPLETE_WITH_ALLOWED_QUALITY";
        return "COMPLETE";
    }

    private static <T> List<String> distinct(
            List<T> values, Function<T, String> extractor) {
        return values.stream().map(extractor).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> String common(
            List<T> values, Function<T, String> extractor, String mixed) {
        List<String> unique = values.stream().map(extractor).filter(Objects::nonNull).distinct().toList();
        return unique.size() == 1 ? unique.getFirst() : mixed;
    }

    private static BigDecimal sum(
            List<MeterDatum> values, Function<MeterDatum, BigDecimal> extractor) {
        return values.stream().map(extractor).reduce(BigDecimal.ZERO,
                (left, right) -> left.add(right, MC));
    }

    private static BigDecimal completeSum(
            List<MeterDatum> values, Function<MeterDatum, BigDecimal> extractor) {
        if (values.stream().map(extractor).anyMatch(Objects::isNull)) return null;
        return sum(values, extractor);
    }

    private boolean targetDimension(QueryDimension value) {
        return value == QueryDimension.SPACE || value == QueryDimension.SYSTEM
                || value == QueryDimension.DEVICE;
    }

    private static void relation(String message) {
        throw error(409, RELATION_UNCONFIRMED, message);
    }

    private record MeasureKey(String relationVersionId, String pointId) {
    }

    private record BoundaryKey(
            String buildingId, String boundaryId, String energyItemCode,
            String energySource, String flowDirection, String periodType,
            java.time.Instant startInclusive, java.time.Instant endExclusive,
            String nativeUnitCode, String tceUnitCode, String conversionPerspective,
            String relationVersionId, long relationModelRevision) {
    }

    private record MeterDatum(
            SnapshotMeasure measure, AssignmentEvidence assignment,
            AssignmentEvidence target) {
        private BoundaryKey boundaryKey() {
            return new BoundaryKey(measure.buildingId(), assignment.meteringBoundaryId(),
                    measure.energyItemCode(), measure.energySource(), assignment.meterDirection(),
                    measure.periodType(), measure.startInclusive(), measure.endExclusive(),
                    measure.nativeUnitCode(), measure.tceUnitCode(), measure.conversionPerspective(),
                    measure.relationVersionId(), measure.relationModelRevision());
        }
    }

    private record ProjectedValue(
            Map<String, String> key, String unit, BigDecimal quantity,
            BigDecimal assigned, BigDecimal unallocated, BigDecimal residual,
            String tceUnit, BigDecimal tce, String flowDirection, BigDecimal coverage,
            int exceptions, String lockStatus, String completeness, String relationVersion,
            String conversionRuleVersion, String summaryPolicyVersion,
            List<String> evidenceHashes, String resultNature, String perspective) {
        private BigDecimal signedTce() {
            return "OUTBOUND".equals(flowDirection) ? tce.negate(MC) : tce;
        }
    }
}
