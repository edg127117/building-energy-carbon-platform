package com.platform.weather.energy;

import com.platform.energy.aggregation.NativeQuantityAggregationService;
import com.platform.energy.aggregation.NativeQuantityAggregationService.NativeCumulativeRequest;
import com.platform.energy.aggregation.NativeQuantityAggregationService.NativeScope;
import com.platform.energy.aggregation.EnergyAggregationModels.ActivityFact;
import com.platform.energy.activity.EnergyActivityPointCatalog;
import com.platform.energy.activity.EnergyActivityPointCatalog.PointProfile;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageSnapshotUnavailableException;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import com.platform.security.FormalRole;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

import static com.platform.energy.aggregation.EnergyAggregationErrors.ANCHOR_MISSING;
import static com.platform.energy.aggregation.EnergyAggregationErrors.CORRECTION_CONFLICT;
import static com.platform.energy.aggregation.EnergyAggregationErrors.EVENT_EVIDENCE_CONFLICT;
import static com.platform.energy.aggregation.EnergyAggregationErrors.INPUT_INCOMPLETE;
import static com.platform.energy.aggregation.EnergyAggregationErrors.NEGATIVE_DELTA_UNCLASSIFIED;
import static com.platform.iot.qualityusage.QualityUsageModels.ENERGY_ACTIVITY_AGGREGATION;

/** 读取真实累计电量，并复用关系、质量和原生累计量治理链生成自然日结果。 */
@Service
public class DailyElectricityAdapter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long MAX_BOUNDARY_AGE_MILLIS = 6 * 60_000L;
    private static final int HISTORY_PAGE_SIZE = 500;
    private static final int MAX_ASSIGNMENTS = 20_000;
    private static final int MAX_FACTS = 250_000;
    private static final int MAX_DAYS = 368;

    private final HvacRawEventRepository rawEvents;
    private final EnergyActivityPointCatalog pointCatalog;
    private final DataPointConfigProvider pointConfigs;
    private final RelationGovernanceService relations;
    private final QualityUsagePolicyResolver qualityResolver;
    private final NativeQuantityAggregationService aggregation;

    public DailyElectricityAdapter(
            HvacRawEventRepository rawEvents,
            EnergyActivityPointCatalog pointCatalog,
            DataPointConfigProvider pointConfigs,
            RelationGovernanceService relations,
            QualityUsagePolicyResolver qualityResolver,
            NativeQuantityAggregationService aggregation) {
        this.rawEvents = rawEvents;
        this.pointCatalog = pointCatalog;
        this.pointConfigs = pointConfigs;
        this.relations = relations;
        this.qualityResolver = qualityResolver;
        this.aggregation = aggregation;
    }

    public List<DailyResult> calculate(
            long userId, Collection<String> roles, String buildingId, String systemId, String pointId,
            LocalDate start, LocalDate end) {
        validateRequest(buildingId, systemId, pointId, start, end);
        List<DailyResult> empty = emptyRange(start, end, "MISSING", "NO_QUALIFIED_BOUNDARIES");
        ObjectSnapshot object = inspectObject(userId, roles, buildingId, systemId, pointId);
        if (!object.valid()) return emptyRange(start, end, "UNCONFIGURED", object.reason());
        RelationSnapshot relation = object.relation();
        PointProfile profile = relation.profile();
        PointRuntimeConfig point = relation.point();

        long from = start.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli() - MAX_BOUNDARY_AGE_MILLIS;
        long endBoundary = end.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        if (relation.effectiveFrom() == null) {
            return emptyRange(start, end, "UNCONFIGURED", "RELATION_EFFECTIVE_TIME_UNAVAILABLE");
        }
        if (endBoundary <= relation.effectiveFrom()) {
            return emptyRange(start, end, "UNCONFIGURED", "RELATION_VERSION_NOT_EFFECTIVE_FOR_DAY");
        }
        long toExclusive;
        try {
            toExclusive = Math.addExact(endBoundary, 1L);
        } catch (ArithmeticException ex) {
            return empty;
        }
        long calculatedAt = System.currentTimeMillis();
        long calculationAsOf = Math.max(calculatedAt, endBoundary);

        List<RawTelemetryEvent> history;
        try {
            history = readHistory(buildingId, pointId, point.equipId(), from, toExclusive);
        } catch (DataAccessException | IllegalStateException | UnsupportedOperationException ex) {
            return emptyRange(start, end, "MISSING", "RAW_HISTORY_UNAVAILABLE");
        }
        if (history.isEmpty()) return emptyRange(start, end, "MISSING", "RAW_HISTORY_EMPTY");

        HistoryQuality qualityFacts;
        ResolutionContext context;
        try {
            context = qualityResolver.historyContext(Set.of(pointId), ENERGY_ACTIVITY_AGGREGATION, from, toExclusive);
            qualityFacts = qualifyHistory(history, context, buildingId, systemId, point, pointId, calculatedAt);
        } catch (QualityUsageSnapshotUnavailableException | IllegalArgumentException ex) {
            return emptyRange(start, end, "MISSING", "QUALITY_POLICY_UNAVAILABLE");
        } catch (DataAccessException | IllegalStateException ex) {
            return emptyRange(start, end, "MISSING", "QUALITY_POLICY_UNAVAILABLE");
        }

        List<DailyResult> results = new ArrayList<>();
        Map<LocalDate, Anchor> boundaries = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end.plusDays(1)); date = date.plusDays(1)) {
            long midnight = date.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
            boundaries.put(date, selectBoundary(qualityFacts.accepted(), midnight));
        }
        String qualityPolicyVersion = String.join(",", qualityFacts.policyVersions());
        String calculationPolicyVersion = "NATIVE_CUMULATIVE_CORE;PROFILE_REV=" + profile.profileRevision()
                + ";ENERGY_ACTIVITY_AGGREGATION=" + qualityPolicyVersion;
        String inputHash = inputHash(history, calculatedAt);
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Anchor left = boundaries.get(day);
            Anchor right = boundaries.get(day.plusDays(1));
            long dayEnd = day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
            if (dayEnd > calculatedAt) {
                results.add(result(day, null, "INCOMPLETE", left, right, relation, qualityPolicyVersion,
                        calculationPolicyVersion, calculatedAt, inputHash, List.of(), "LOCAL_DAY_NOT_ENDED"));
                continue;
            }
            long dayStart = day.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
            if (relation.effectiveFrom() == null || dayStart < relation.effectiveFrom()) {
                results.add(result(day, null, "UNCONFIGURED", left, right, relation, qualityPolicyVersion,
                        calculationPolicyVersion, calculatedAt, inputHash, List.of(),
                        relation.effectiveFrom() == null ? "RELATION_EFFECTIVE_TIME_UNAVAILABLE"
                                : "RELATION_VERSION_NOT_EFFECTIVE_FOR_DAY"));
                continue;
            }
            if (left == null || right == null) {
                results.add(result(day, null, "MISSING", left, right, relation, qualityPolicyVersion,
                        calculationPolicyVersion, calculatedAt, inputHash, List.of(), "QUALIFIED_BOUNDARY_MISSING"));
                continue;
            }
            if(left.time()<relation.effectiveFrom()) {
                results.add(result(day,null,"UNCONFIGURED",left,right,relation,qualityPolicyVersion,
                        calculationPolicyVersion,calculatedAt,inputHash,List.of(),"BOUNDARY_PRECEDES_RELATION_VERSION"));
                continue;
            }
            long identities=history.stream().filter(e->e.eventTime()>=left.time()&&e.eventTime()<=right.time())
                    .map(e->Objects.toString(e.sourceSystem(),"")+"|"+Objects.toString(e.sourcePointCode(),"")+"|"+Objects.toString(e.sourceDeviceId(),""))
                    .distinct().limit(2).count();
            if(identities>1) {
                // 原生累计核心不接收来源设备身份，不能把两台计数器仅凭数值连续视为同表。
                results.add(result(day,null,"UNRESOLVED",left,right,relation,qualityPolicyVersion,
                        calculationPolicyVersion,calculatedAt,inputHash,List.of(),"SOURCE_IDENTITY_CHANGED_REQUIRES_REVIEW"));
                continue;
            }
            if (right.time() <= left.time()) {
                results.add(result(day, null, "MISSING", left, right, relation, qualityPolicyVersion,
                        calculationPolicyVersion, calculatedAt, inputHash, List.of(), "BOUNDARY_ORDER_INVALID"));
                continue;
            }
            if (qualityFacts.blockedTimes().stream().anyMatch(time -> time >= left.time() && time <= right.time())) {
                results.add(result(day, null, "QUALITY_BLOCKED", left, right, relation, qualityPolicyVersion,
                        calculationPolicyVersion, calculatedAt, inputHash, List.of(), "QUALITY_POLICY_REJECTED_RAW_READING"));
                continue;
            }
            List<ActivityFact> facts = factsBetween(qualityFacts.accepted(), left.time(), right.time());
            if (facts.isEmpty() || !facts.getFirst().eventTime().equals(Instant.ofEpochMilli(left.time()))
                    || !facts.getLast().eventTime().equals(Instant.ofEpochMilli(right.time()))) {
                results.add(result(day, null, "MISSING", left, right, relation, qualityPolicyVersion,
                        calculationPolicyVersion, calculatedAt, inputHash, List.of(), "QUALIFIED_BOUNDARY_MISSING"));
                continue;
            }
            List<String> evidenceReferences = List.of();
            try {
                Instant fromInstant = Instant.ofEpochMilli(left.time());
                Instant toInstant = Instant.ofEpochMilli(right.time());
                NativeQuantityAggregationService.GovernedEvidence evidence = aggregation.governedEvidence(
                        userId, roles, buildingId, pointId, fromInstant, toInstant);
                evidenceReferences = governedEvidenceReferences(evidence.meterEvents(), evidence.corrections());
                long watermark = facts.stream().mapToLong(fact -> fact.receivedTime().toEpochMilli()).max()
                        .orElse(right.time());
                var nativeResult = aggregation.aggregateCumulative(new NativeCumulativeRequest(
                        new NativeScope(buildingId, pointId, fromInstant, toInstant,
                                Instant.ofEpochMilli(calculationAsOf), Instant.ofEpochMilli(watermark)),
                        "kWh", facts, evidence.meterEvents(), evidence.corrections()));
                boolean approximate = left.approximate() || right.approximate();
                results.add(result(day, nativeResult.quantity(), approximate ? "APPROXIMATE" : "COMPLETE",
                        left, right, relation, qualityPolicyVersion, calculationPolicyVersion, calculatedAt,
                        inputHash,
                        evidenceReferences(nativeResult.meterEventVersionIds(), nativeResult.correctionVersionIds()).isEmpty()
                                ? evidenceReferences
                                : evidenceReferences(nativeResult.meterEventVersionIds(), nativeResult.correctionVersionIds()),
                        approximate ? "BOUNDARY_WITHIN_PRECEDING_SIX_MINUTES" : null));
            } catch (BusinessException ex) {
                String reason = switch (Objects.toString(ex.getErrorCode(), "")) {
                    case NEGATIVE_DELTA_UNCLASSIFIED -> "CUMULATIVE_READING_DECREASE_UNRESOLVED";
                    case EVENT_EVIDENCE_CONFLICT -> "METER_EVENT_EVIDENCE_CONFLICT";
                    case CORRECTION_CONFLICT -> "CORRECTION_EVIDENCE_CONFLICT";
                    case ANCHOR_MISSING -> "QUALIFIED_BOUNDARY_MISSING";
                    case INPUT_INCOMPLETE -> "CUMULATIVE_EVIDENCE_INCOMPLETE";
                    default -> null;
                };
                if (reason != null) {
                    results.add(result(day, null, "UNRESOLVED", left, right, relation, qualityPolicyVersion,
                            calculationPolicyVersion, calculatedAt, inputHash, evidenceReferences, reason));
                } else {
                    throw ex;
                }
            }
        }
        return List.copyOf(results);
    }

    /** 只验证授权范围和测点归属，不读取 TDengine 历史，也不执行电量计算。 */
    public ObjectValidation validateObject(
            long userId, Collection<String> roles, String buildingId, String systemId, String pointId) {
        if (blank(buildingId) || blank(systemId) || blank(pointId)) {
            throw new IllegalArgumentException("建筑、系统和测点不能为空");
        }
        if (!hasReaderRole(roles)) {
            throw new BusinessException(403, "ENERGY_AGGREGATION_FORBIDDEN", "当前角色不能读取建筑计量对象");
        }
        ObjectSnapshot object = inspectObject(userId, roles, buildingId, systemId, pointId);
        if (!object.valid()) {
            return new ObjectValidation(false, buildingId, systemId, pointId, null, null,
                    null, null, null, object.reason());
        }
        RelationSnapshot relation = object.relation();
        return new ObjectValidation(true, buildingId, systemId, pointId, relation.profile().unit(),
                relation.direction(), relation.versionId(), relation.effectiveFrom(),
                relation.profile().profileRevision(), null);
    }

    private ObjectSnapshot inspectObject(
            long userId, Collection<String> roles, String buildingId, String systemId, String pointId) {
        if (!hasReaderRole(roles)) {
            throw new BusinessException(403, "ENERGY_AGGREGATION_FORBIDDEN", "当前角色不能读取日电量");
        }
        EffectiveAssignments effective;
        try {
            effective = loadEffectiveAssignments(userId, roles, buildingId);
        } catch (DataAccessException | IllegalStateException ex) {
            return new ObjectSnapshot(false, null, "EFFECTIVE_RELATION_UNAVAILABLE");
        }
        if (effective == null) {
            return new ObjectSnapshot(false, null, "EFFECTIVE_METER_ASSIGNMENT_MISSING_OR_UNCONFIRMED");
        }
        PointRuntimeConfig point;
        PointProfile profile;
        try {
            point = pointConfigs.findByPointId(pointId).orElse(null);
            List<PointProfile> profiles = pointCatalog.find(buildingId, Set.of(pointId));
            profile = profiles.stream().filter(item -> pointId.equals(item.pointId())).findFirst().orElse(null);
        } catch (DataAccessException | IllegalStateException ex) {
            return new ObjectSnapshot(false, null, "POINT_CONFIGURATION_UNAVAILABLE");
        }
        if (!validPoint(buildingId, systemId, pointId, profile, point)) {
            return new ObjectSnapshot(false, null, "POINT_SYSTEM_OR_CUMULATIVE_KWH_PROFILE_UNCONFIRMED");
        }
        RelationSnapshot relation = resolveRelation(effective, buildingId, systemId, pointId, point, profile);
        if (relation == null) {
            return new ObjectSnapshot(false, null, "EFFECTIVE_METER_ASSIGNMENT_MISSING_OR_UNCONFIRMED");
        }
        return new ObjectSnapshot(true, relation, null);
    }

    private RelationSnapshot resolveRelation(
            EffectiveAssignments effective, String buildingId, String systemId, String pointId,
            PointRuntimeConfig config, PointProfile profile) {
        if (!buildingId.equals(config.buildingId()) || !systemId.equals(config.systemGroupId())) {
            return null;
        }
        List<MeteringAssignmentView> matches = effective.assignments().stream()
                .filter(item -> Objects.equals(pointId, item.pointId()))
                .filter(item -> "ASSIGNED".equals(item.allocationStatus()))
                .filter(item -> "ELECTRICITY".equals(item.energyType()))
                .filter(item -> "ACTIVE".equals(item.boundaryStatus()))
                .filter(item -> "CONFIRMED".equals(item.boundaryConfirmationStatus())
                        && "CONFIRMED".equals(item.meterConfirmationStatus()))
                .filter(item -> "INBOUND".equals(item.meterDirection()))
                .filter(item -> targetMatches(item, systemId, config.equipId()))
                .toList();
        if (matches.size() != 1) return null;
        return new RelationSnapshot(effective.versionId(), effective.effectiveFrom(),
                matches.getFirst().meterDirection(), config, profile);
    }

    private EffectiveAssignments loadEffectiveAssignments(
            long userId, Collection<String> roles, String buildingId) {
        List<MeteringAssignmentView> assignments = new ArrayList<>();
        String versionId = null;
        Long effectiveFrom = null;
        int page = 1;
        long total;
        do {
            MeteringAssignmentsView response = relations.effectiveMeteringAssignments(
                    userId, roles, buildingId, page, HISTORY_PAGE_SIZE);
            if (response == null || response.metadata() == null || response.items() == null) return null;
            if (versionId == null) versionId = response.metadata().versionId();
            if (!Objects.equals(versionId, response.metadata().versionId())) return null;
            Long pageEffectiveFrom = epoch(response.metadata().effectiveAt());
            if (effectiveFrom == null) effectiveFrom = pageEffectiveFrom;
            if (!Objects.equals(effectiveFrom, pageEffectiveFrom)) return null;
            assignments.addAll(response.items());
            total = response.total();
            if (assignments.size() > MAX_ASSIGNMENTS) return null;
            if (response.items().isEmpty() && assignments.size() < total) return null;
            page++;
        } while (assignments.size() < total);
        return new EffectiveAssignments(versionId, effectiveFrom, List.copyOf(assignments));
    }

    private static boolean targetMatches(MeteringAssignmentView item, String systemId, String equipmentId) {
        if ("SYSTEM".equals(item.targetNodeType())) return systemId.equals(item.targetObjectId());
        return "EQUIPMENT".equals(item.targetNodeType()) && equipmentId != null
                && equipmentId.equals(item.targetObjectId());
    }

    private static boolean validPoint(
            String buildingId, String systemId, String pointId, PointProfile profile, PointRuntimeConfig config) {
        return profile != null && config != null
                && pointId.equals(profile.pointId()) && pointId.equals(config.pointId())
                && buildingId.equals(config.buildingId())
                && systemId.equals(config.systemGroupId())
                && "ELECTRICITY".equals(profile.energyType())
                && "CUMULATIVE".equals(profile.valueSemantics())
                && "CONFIRMED".equals(profile.confirmationStatus())
                && "kWh".equals(profile.unit()) && "kWh".equals(config.unit());
    }

    private List<RawTelemetryEvent> readHistory(
            String buildingId, String pointId, String equipmentId, long from, long toExclusive) {
        List<RawTelemetryEvent> all = new ArrayList<>();
        Long after = null;
        while (true) {
            List<RawTelemetryEvent> page = rawEvents.findPointHistory(
                    buildingId, equipmentId, pointId, from, toExclusive, after, HISTORY_PAGE_SIZE);
            if (page == null || page.isEmpty()) break;
            all.addAll(page);
            if (all.size() > MAX_FACTS) throw new IllegalStateException("raw history exceeds single request limit");
            long next = page.getLast().eventTime();
            if(after!=null&&next<=after)throw new IllegalStateException("raw history cursor did not advance");
            if (page.size() < HISTORY_PAGE_SIZE) break;
            after = next;
        }
        return all;
    }

    private HistoryQuality qualifyHistory(
            List<RawTelemetryEvent> events, ResolutionContext context,
            String buildingId, String systemId, PointRuntimeConfig config, String pointId, long calculationAsOf) {
        Map<Long, QualifiedEvent> accepted = new LinkedHashMap<>();
        Set<Long> blocked = new LinkedHashSet<>();
        Set<String> policyVersions = new TreeSet<>();
        for (RawTelemetryEvent event : events) {
            if (event == null || !buildingId.equals(event.buildingId()) || !pointId.equals(event.pointId())
                    || !systemId.equals(event.systemGroupId()) || !Objects.equals(config.equipId(), event.equipId())) {
                throw new IllegalStateException("raw event identity does not match the configured point");
            }
            if (event.receivedTime() > calculationAsOf) continue;
            Resolution resolution = qualityResolver.resolve(context, pointId, ENERGY_ACTIVITY_AGGREGATION,
                    QualityUsagePolicyResolver.alignMinute(event.eventTime()), event.dataQuality());
            policyVersions.add(resolution.policySource().name() + ":"
                    + Objects.toString(resolution.policyVersion(), "DEFAULT"));
            if (resolution.decision() == Decision.BLOCK) {
                blocked.add(event.eventTime());
                continue;
            }
            BigDecimal raw = BigDecimal.valueOf(event.value());
            accepted.putIfAbsent(event.eventTime(), new QualifiedEvent(event, raw, resolution));
        }
        return new HistoryQuality(accepted, blocked, List.copyOf(policyVersions));
    }

    private static Anchor selectBoundary(Map<Long, QualifiedEvent> accepted, long midnight) {
        QualifiedEvent exact = accepted.get(midnight);
        if (exact != null) return new Anchor(midnight, exact.rawValue(), false);
        Map.Entry<Long, QualifiedEvent> previous = accepted.entrySet().stream()
                .filter(entry -> entry.getKey() < midnight && midnight - entry.getKey() <= MAX_BOUNDARY_AGE_MILLIS)
                .max(Map.Entry.comparingByKey()).orElse(null);
        return previous == null ? null : new Anchor(previous.getKey(), previous.getValue().rawValue(), true);
    }

    private static List<ActivityFact> factsBetween(Map<Long, QualifiedEvent> qualified, long start, long end) {
        return qualified.entrySet().stream().filter(entry -> entry.getKey() >= start && entry.getKey() <= end)
                .sorted(Map.Entry.comparingByKey()).map(entry -> {
                    RawTelemetryEvent event = entry.getValue().event();
                    Resolution resolution = entry.getValue().resolution();
                    return new ActivityFact(event.pointId() + "@" + event.eventTime(), entry.getValue().rawValue(),
                            Instant.ofEpochMilli(event.eventTime()), Instant.ofEpochMilli(event.receivedTime()),
                            QualityLevel.fromCode(event.dataQuality()).name(),
                            Objects.toString(resolution.policyVersion(), resolution.policySource().name()),
                            event.late(), null, null, null, null);
                }).toList();
    }

    private static String inputHash(List<RawTelemetryEvent> events, long asOf) {
        String canonical = events.stream().filter(event -> event != null && event.receivedTime() <= asOf)
                .sorted(java.util.Comparator.comparingLong(RawTelemetryEvent::eventTime))
                .map(event -> event.pointId() + "@" + event.eventTime() + ":"
                        + BigDecimal.valueOf(event.value()).toPlainString() + ":"
                        + event.receivedTime() + ":" + event.dataQuality())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<String> evidenceReferences(List<String> eventVersions, List<String> correctionVersions) {
        List<String> refs = new ArrayList<>();
        eventVersions.forEach(version -> refs.add("METER_EVENT:" + version));
        correctionVersions.forEach(version -> refs.add("CORRECTION:" + version));
        return List.copyOf(refs);
    }

    private static List<String> governedEvidenceReferences(
            List<com.platform.energy.aggregation.EnergyAggregationModels.MeterEventEvidence> events,
            List<com.platform.energy.aggregation.EnergyAggregationModels.CorrectionEvidence> corrections) {
        return evidenceReferences(events.stream().map(event -> event.eventVersionId()).toList(),
                corrections.stream().map(correction -> correction.correctionVersionId()).toList());
    }

    private static DailyResult result(
            LocalDate day, BigDecimal energy, String status, Anchor start, Anchor end,
            RelationSnapshot relation, String qualityPolicyVersion, String calculationPolicyVersion,
            long calculatedAt, String inputHash, List<String> evidenceReferences, String reason) {
        List<String> reasons = reason == null ? List.of() : List.of(reason);
        return new DailyResult(day, energy, status,
                start != null && start.approximate() || end != null && end.approximate(),
                start == null ? null : start.time(), end == null ? null : end.time(),
                start == null ? null : start.reading(), end == null ? null : end.reading(),
                relation == null ? null : relation.versionId(), qualityPolicyVersion,
                relation == null ? null : relation.direction(), "kWh",
                start == null ? null : start.time() - day.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli(),
                end == null ? null : end.time() - day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli(),
                calculatedAt, inputHash, evidenceReferences, calculationPolicyVersion, reasons);
    }

    private static List<DailyResult> emptyRange(LocalDate start, LocalDate end, String status, String reason) {
        List<DailyResult> values = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            values.add(new DailyResult(day, null, status, false, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, List.of(reason)));
        }
        return List.copyOf(values);
    }

    private static void validateRequest(String buildingId, String systemId, String pointId,
                                        LocalDate start, LocalDate end) {
        if (blank(buildingId) || blank(systemId) || blank(pointId) || start == null || end == null
                || end.isBefore(start) || start.plusDays(MAX_DAYS - 1L).isBefore(end)) {
            throw new IllegalArgumentException("建筑、系统、测点和不超过 366 天的有效日期范围为必填项");
        }
    }

    private static boolean hasReaderRole(Collection<String> roles) {
        if (roles == null) return false;
        return roles.stream().filter(Objects::nonNull).map(String::trim).anyMatch(role ->
                role.equalsIgnoreCase(FormalRole.BUILDING_OWNER.name())
                        || role.equalsIgnoreCase(FormalRole.ENERGY_MANAGER.name())
                        || role.equalsIgnoreCase(FormalRole.PLATFORM_ADMIN.name()));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static Long epoch(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
    }

    public record DailyResult(
            LocalDate day, BigDecimal energyKwh, String status, boolean approximate,
            Long startTime, Long endTime, BigDecimal startReading, BigDecimal endReading,
            String relationVersion, String qualityPolicyVersion, String direction, String unit,
            Long startBoundaryOffsetMillis, Long endBoundaryOffsetMillis, Long calculatedAt,
            String inputSummaryHash, List<String> governanceEvidenceReferences,
            String calculationPolicyVersion, List<String> reasons) {
        public DailyResult { reasons = List.copyOf(reasons); }
    }

    public record ObjectValidation(boolean valid, String buildingId, String systemId, String pointId,
                                   String unit, String direction, String relationVersion, Long relationEffectiveAt,
                                   Integer profileRevision, String reason) { }

    private record ObjectSnapshot(boolean valid, RelationSnapshot relation, String reason) { }
    private record EffectiveAssignments(String versionId, Long effectiveFrom,
                                        List<MeteringAssignmentView> assignments) { }
    private record RelationSnapshot(String versionId, Long effectiveFrom, String direction, PointRuntimeConfig point,
                                    PointProfile profile) { }
    private record Anchor(long time, BigDecimal reading, boolean approximate) { }
    private record QualifiedEvent(RawTelemetryEvent event, BigDecimal rawValue, Resolution resolution) { }
    private record HistoryQuality(Map<Long, QualifiedEvent> accepted, Set<Long> blockedTimes,
                                  List<String> policyVersions) { }

}
