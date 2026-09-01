package com.platform.energy.aggregation;

import com.platform.energy.aggregation.EnergyAggregationModels.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.platform.energy.aggregation.EnergyAggregationErrors.*;

@Component
/** 对累计量、周期量和瞬时量执行失败关闭的确定性十进制聚合。 */
public class EnergyAggregationCore {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal SECONDS_PER_HOUR = new BigDecimal("3600");

    public AggregationResult aggregate(AggregationInput input) {
        validateCommon(input);
        List<ActivityFact> facts = correctedFacts(input.facts(), input.corrections());
        Computation computation = switch (input.measurement().valueSemantics()) {
            case CUMULATIVE -> cumulative(input.query(), facts, input.meterEvents());
            case PERIOD_TOTAL -> periodTotal(input.query(), facts);
            case INSTANTANEOUS -> instantaneous(input.query(), facts, input.integrationPolicy());
        };
        return result(input, computation);
    }

    private static void validateCommon(AggregationInput input) {
        if (input == null || input.query() == null || input.measurement() == null
                || input.assignment() == null || input.activityWatermark() == null) {
            throw error(INPUT_INCOMPLETE, "聚合输入及版本证据不完整");
        }
        AggregationQuery query = input.query();
        if (blank(query.buildingId()) || blank(query.pointId()) || query.startInclusive() == null
                || query.endExclusive() == null || query.calculationAsOf() == null
                || !query.startInclusive().isBefore(query.endExclusive())
                || query.calculationAsOf().isBefore(query.endExclusive())
                || input.activityWatermark().isAfter(query.calculationAsOf())) {
            throw error(INPUT_INCOMPLETE, "聚合时间范围或对象无效");
        }
        MeasurementContext context = input.measurement();
        if (!"CONFIRMED".equals(context.confirmationStatus())
                || blank(context.energyItemCode()) || blank(context.pointBindingVersionId())
                || blank(context.sourceUnitCode()) || blank(context.unitDefinitionVersionId())
                || context.effectiveFrom() == null
                || context.effectiveFrom().isAfter(query.startInclusive())
                || context.effectiveTo() != null
                && context.effectiveTo().isBefore(query.endExclusive())) {
            throw error(INPUT_INCOMPLETE, "测量上下文未确认或版本证据不完整");
        }
        MeteringAssignmentEvidence assignment = input.assignment();
        if (!"ASSIGNED".equals(assignment.allocationStatus())
                || !"CONFIRMED".equals(assignment.confirmationStatus())
                || blank(assignment.relationVersionId()) || blank(assignment.assignmentItemId())) {
            throw error(INPUT_INCOMPLETE, "有效计量分配未确认");
        }
        if (context.dataNature() != DataNature.SIMULATED) {
            throw error(INPUT_INCOMPLETE, "当前切片只允许研发模拟输入");
        }
        for (ActivityFact fact : input.facts()) {
            if (fact.receivedTime() == null || fact.receivedTime().isAfter(input.activityWatermark())) {
                throw error(INPUT_INCOMPLETE, "活动事实超出本次固定水位");
            }
        }
        for (MeterEventEvidence event : input.meterEvents()) {
            if (event.status() == EvidenceStatus.APPROVED
                    && (!query.buildingId().equals(event.buildingId())
                    || !query.pointId().equals(event.meterPointId()))) {
                throw error(EVENT_EVIDENCE_CONFLICT, "计量事件不属于本次建筑和测点");
            }
        }
    }

    private static List<ActivityFact> correctedFacts(
            List<ActivityFact> source, List<CorrectionEvidence> corrections) {
        LinkedHashSet<String> factIdentities = new LinkedHashSet<>();
        for (ActivityFact fact : source) {
            if (!factIdentities.add(fact.factIdentity())) {
                throw error(INPUT_INCOMPLETE, "活动事实身份重复");
            }
        }
        Map<String, CorrectionEvidence> approved = new HashMap<>();
        for (CorrectionEvidence correction : corrections) {
            if (correction.status() != EvidenceStatus.APPROVED) continue;
            if (blank(correction.correctionVersionId()) || blank(correction.evidenceReference())
                    || correction.approvedBy() == null) {
                throw error(INPUT_INCOMPLETE, "已审核修正缺少版本、审核或来源证据");
            }
            if (!factIdentities.contains(correction.originalFactIdentity())) {
                throw error(CORRECTION_CONFLICT, "审核修正引用的原始事实不存在");
            }
            if (!correction.qualityGatePassed()) {
                throw error(INPUT_INCOMPLETE, "审核修正尚未重新通过聚合质量门禁");
            }
            CorrectionEvidence previous = approved.putIfAbsent(correction.originalFactIdentity(), correction);
            if (previous != null) {
                throw error(CORRECTION_CONFLICT, "同一原始事实存在多个已审核修正");
            }
        }
        List<ActivityFact> result = new ArrayList<>();
        for (ActivityFact fact : source) {
            if (blank(fact.factIdentity()) || fact.rawValue() == null || fact.eventTime() == null
                    || blank(fact.qualityLevel()) || blank(fact.qualityPolicyVersion())) {
                throw error(INPUT_INCOMPLETE, "活动事实或质量策略证据不完整");
            }
            CorrectionEvidence correction = approved.get(fact.factIdentity());
            BigDecimal value = fact.rawValue();
            if (correction != null) {
                if (!same(value, correction.originalValue()) || correction.correctedValue() == null) {
                    throw error(CORRECTION_CONFLICT, "修正证据与原始事实不一致");
                }
                value = correction.correctedValue();
            }
            result.add(new ActivityFact(fact.factIdentity(), value, fact.eventTime(), fact.receivedTime(),
                    fact.qualityLevel(), fact.qualityPolicyVersion(), fact.late(), fact.sourcePeriodStart(),
                    fact.sourcePeriodEnd(), fact.sourcePeriodTimezone(), fact.periodDefinitionVersion()));
        }
        result.sort(Comparator.comparing(ActivityFact::eventTime).thenComparing(ActivityFact::factIdentity));
        return result;
    }

    private static Computation cumulative(
            AggregationQuery query, List<ActivityFact> facts, List<MeterEventEvidence> events) {
        List<ActivityFact> readings = facts.stream()
                .filter(v -> !v.eventTime().isAfter(query.endExclusive()))
                .toList();
        int startAnchor = -1;
        for (int i = 0; i < readings.size(); i++) {
            if (!readings.get(i).eventTime().isAfter(query.startInclusive())) startAnchor = i;
        }
        if (startAnchor < 0 || startAnchor == readings.size() - 1
                || readings.getLast().eventTime().isBefore(query.endExclusive())) {
            throw error(ANCHOR_MISSING, "累计量缺少开始锚点或结束边界读数");
        }
        BigDecimal total = BigDecimal.ZERO;
        long maxGap = 0;
        for (int i = startAnchor + 1; i < readings.size(); i++) {
            ActivityFact previous = readings.get(i - 1);
            ActivityFact current = readings.get(i);
            if (current.eventTime().isBefore(query.startInclusive())) continue;
            maxGap = Math.max(maxGap, seconds(previous.eventTime(), current.eventTime()));
            BigDecimal delta = current.rawValue().subtract(previous.rawValue(), MC);
            if (delta.signum() >= 0) {
                total = total.add(delta, MC);
                continue;
            }
            total = total.add(classifiedNegative(previous, current, events), MC);
        }
        return new Computation(total, BigDecimal.ONE, maxGap, null);
    }

    private static BigDecimal classifiedNegative(
            ActivityFact previous, ActivityFact current, List<MeterEventEvidence> events) {
        List<MeterEventEvidence> matched = events.stream()
                .filter(v -> v.status() == EvidenceStatus.APPROVED)
                .filter(v -> !v.occurredAt().isBefore(previous.eventTime())
                        && !v.occurredAt().isAfter(current.eventTime()))
                .toList();
        if (matched.isEmpty()) {
            throw error(NEGATIVE_DELTA_UNCLASSIFIED, "累计表负增量尚未分类");
        }
        if (matched.size() != 1) {
            throw error(EVENT_EVIDENCE_CONFLICT, "负增量匹配到多个已审核计量事件");
        }
        MeterEventEvidence event = matched.getFirst();
        if (blank(event.eventVersionId()) || blank(event.evidenceReference())
                || event.approvedBy() == null || !event.simulationFlag()
                || blank(event.buildingId()) || blank(event.meterPointId())) {
            throw error(INPUT_INCOMPLETE, "已审核计量事件缺少版本、审核或模拟证据");
        }
        return switch (event.eventType()) {
            case RESET -> segmented(previous.rawValue(), current.rawValue(), event, "复位");
            case ROLLOVER -> rollover(previous.rawValue(), current.rawValue(), event);
            case REPLACEMENT -> segmented(previous.rawValue(), current.rawValue(), event, "换表");
            case DATA_ERROR -> throw error(NEGATIVE_DELTA_UNCLASSIFIED, "数据错误必须通过审核修正处理");
        };
    }

    private static BigDecimal segmented(
            BigDecimal previous, BigDecimal current, MeterEventEvidence event, String type) {
        if (event.preEventReading() == null || event.postEventReading() == null) {
            throw error(INPUT_INCOMPLETE, type + "事件缺少前后读数");
        }
        BigDecimal before = event.preEventReading().subtract(previous, MC);
        BigDecimal after = current.subtract(event.postEventReading(), MC);
        if (before.signum() < 0 || after.signum() < 0) {
            throw error(EVENT_EVIDENCE_CONFLICT, type + "事件读数不能形成有效分段");
        }
        if (event.eventType() == MeterEventType.REPLACEMENT
                && (blank(event.oldMeterId()) || blank(event.newMeterId())
                || blank(event.relationVersionBefore()) || blank(event.relationVersionAfter()))) {
            throw error(INPUT_INCOMPLETE, "换表事件缺少表计身份或前后关系版本");
        }
        return before.add(after, MC);
    }

    private static BigDecimal rollover(
            BigDecimal previous, BigDecimal current, MeterEventEvidence event) {
        if (event.rolloverModulus() == null || event.rolloverModulus().signum() <= 0) {
            throw error(INPUT_INCOMPLETE, "回绕事件缺少有效计数周期");
        }
        BigDecimal delta = event.rolloverModulus().subtract(previous, MC).add(current, MC);
        if (delta.signum() < 0) {
            throw error(EVENT_EVIDENCE_CONFLICT, "回绕计数周期小于事件前读数");
        }
        return delta;
    }

    private static Computation periodTotal(AggregationQuery query, List<ActivityFact> facts) {
        List<ActivityFact> periods = facts.stream()
                .filter(v -> v.sourcePeriodStart() != null && v.sourcePeriodEnd() != null)
                .sorted(Comparator.comparing(ActivityFact::sourcePeriodStart))
                .toList();
        if (periods.size() != facts.size() || periods.isEmpty()) {
            throw error(PERIOD_COVERAGE_INVALID, "周期量缺少显式源周期边界");
        }
        Instant cursor = query.startInclusive();
        BigDecimal total = BigDecimal.ZERO;
        for (ActivityFact fact : periods) {
            if (blank(fact.sourcePeriodTimezone()) || blank(fact.periodDefinitionVersion())
                    || !fact.sourcePeriodStart().equals(cursor)
                    || !fact.sourcePeriodStart().isBefore(fact.sourcePeriodEnd())
                    || fact.sourcePeriodEnd().isAfter(query.endExclusive())) {
                throw error(PERIOD_COVERAGE_INVALID, "周期量存在间断、重叠或范围越界");
            }
            cursor = fact.sourcePeriodEnd();
            total = total.add(fact.rawValue(), MC);
        }
        if (!cursor.equals(query.endExclusive())) {
            throw error(PERIOD_COVERAGE_INVALID, "周期量未完整覆盖目标周期");
        }
        return new Computation(total, BigDecimal.ONE, 0, null);
    }

    private static Computation instantaneous(
            AggregationQuery query, List<ActivityFact> facts, IntegrationPolicy policy) {
        if (policy == null || policy.status() != EvidenceStatus.APPROVED
                || policy.method() == null || policy.boundaryHandling() == null
                || policy.maximumGapSeconds() <= 0 || policy.minimumCoverageRatio() == null
                || policy.minimumCoverageRatio().signum() < 0
                || policy.minimumCoverageRatio().compareTo(BigDecimal.ONE) > 0) {
            throw error(INTEGRATION_POLICY_REQUIRED, "瞬时量缺少已审核积分策略");
        }
        List<ActivityFact> points = facts.stream()
                .filter(v -> !v.eventTime().isBefore(query.startInclusive())
                        && !v.eventTime().isAfter(query.endExclusive()))
                .toList();
        if (points.size() < 2 || !points.getFirst().eventTime().equals(query.startInclusive())
                || !points.getLast().eventTime().equals(query.endExclusive())) {
            throw error(ANCHOR_MISSING, "瞬时量缺少周期边界读数");
        }
        long covered = 0;
        long maxGap = 0;
        BigDecimal wattHours = BigDecimal.ZERO;
        for (int i = 1; i < points.size(); i++) {
            ActivityFact previous = points.get(i - 1);
            ActivityFact current = points.get(i);
            long gap = seconds(previous.eventTime(), current.eventTime());
            maxGap = Math.max(maxGap, gap);
            if (gap > policy.maximumGapSeconds()) continue;
            covered += gap;
            BigDecimal power = policy.method() == IntegrationMethod.STEP_PREVIOUS
                    ? previous.rawValue()
                    : previous.rawValue().add(current.rawValue(), MC).divide(new BigDecimal("2"), MC);
            wattHours = wattHours.add(power.multiply(BigDecimal.valueOf(gap), MC)
                    .divide(SECONDS_PER_HOUR, MC), MC);
        }
        long target = seconds(query.startInclusive(), query.endExclusive());
        BigDecimal coverage = BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(target), MC);
        if (coverage.compareTo(policy.minimumCoverageRatio()) < 0) {
            throw error(COVERAGE_INSUFFICIENT, "瞬时量有效积分覆盖率不足");
        }
        return new Computation(wattHours, coverage, maxGap, policy.policyVersionId());
    }

    private static AggregationResult result(AggregationInput input, Computation value) {
        LinkedHashSet<String> qualityVersions = new LinkedHashSet<>();
        input.facts().forEach(v -> qualityVersions.add(v.qualityPolicyVersion()));
        List<String> eventVersions = input.meterEvents().stream()
                .filter(v -> v.status() == EvidenceStatus.APPROVED)
                .map(MeterEventEvidence::eventVersionId).filter(Objects::nonNull).distinct().toList();
        List<String> correctionVersions = input.corrections().stream()
                .filter(v -> v.status() == EvidenceStatus.APPROVED)
                .map(CorrectionEvidence::correctionVersionId).filter(Objects::nonNull).distinct().toList();
        String unit = input.measurement().valueSemantics() == ValueSemantics.INSTANTANEOUS
                ? integratedUnit(input.measurement().sourceUnitCode()) : input.measurement().sourceUnitCode();
        boolean allowedQuality = input.facts().stream().anyMatch(v -> !"Q0".equals(v.qualityLevel()));
        return new AggregationResult("DEVELOPMENT_SIMULATION", input.query().buildingId(),
                input.query().pointId(), input.measurement().energyItemCode(),
                input.measurement().valueSemantics(), value.quantity(), unit, value.coverage(),
                value.maxGap(), allowedQuality ? ResultCompleteness.COMPLETE_WITH_ALLOWED_QUALITY
                        : ResultCompleteness.COMPLETE,
                input.query().calculationAsOf(), input.activityWatermark(),
                input.assignment().relationVersionId(), input.measurement().pointBindingVersionId(),
                List.copyOf(qualityVersions), eventVersions, correctionVersions, value.policyVersion());
    }

    private static String integratedUnit(String sourceUnit) {
        if ("KW".equalsIgnoreCase(sourceUnit)) return "KWH";
        throw error(INPUT_INCOMPLETE, "首版瞬时量积分只接受 kW 并输出 kWh");
    }

    private static long seconds(Instant from, Instant to) {
        long seconds = Duration.between(from, to).getSeconds();
        if (seconds <= 0) throw error(INPUT_INCOMPLETE, "活动事实时间必须严格递增");
        return seconds;
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Computation(
            BigDecimal quantity, BigDecimal coverage, long maxGap, String policyVersion) {
    }
}
