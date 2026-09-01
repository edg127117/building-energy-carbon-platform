package com.platform.energy.aggregation;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.energy.activity.EnergyActivityDataContracts.AggregationActivitySnapshot;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.aggregation.EnergyAggregationGovernanceRepository.*;
import com.platform.energy.aggregation.EnergyAggregationModels.*;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.platform.energy.aggregation.EnergyAggregationErrors.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 治理只追加计量事件、活动修正和积分策略版本，不覆盖原始活动事实。 */
public class EnergyAggregationGovernanceService {
    private static final Set<String> EVENT_SOURCES = Set.of("SIMULATION", "MANUAL", "DEVICE", "IMPORT");
    private static final Set<String> POLICY_SOURCES = Set.of("SIMULATION", "MANUAL", "STANDARD");

    private final EnergyAggregationAuthorization authorization;
    private final EnergyAggregationGovernanceRepository repository;
    private final EnergyActivityDataService activityDataService;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    public List<MeterEventVersionView> listEvents(
            long userId, Collection<String> roles, String buildingId, String pointId) {
        read(userId, roles, buildingId);
        return repository.listEventVersions(required(buildingId, "建筑不能为空"),
                required(pointId, "测点不能为空")).stream().map(this::eventView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public MeterEventVersionView createEvent(
            long userId, Collection<String> roles, CreateMeterEventVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String building = required(request.buildingId(), "建筑不能为空");
        String point = required(request.meterPointId(), "测点不能为空");
        authorization.checkBuilding(userId, roles, building);
        MeterEventType type = parse(MeterEventType.class, request.eventType(), "计量事件类型无效");
        String source = allowed(request.sourceType(), EVENT_SOURCES, "计量事件来源无效");
        boolean simulation = Boolean.TRUE.equals(request.simulationFlag());
        if (("SIMULATION".equals(source)) != simulation) {
            validation("模拟来源和模拟标记必须一致");
        }
        validateEvent(type, request);
        LocalDateTime now = LocalDateTime.now();
        String eventId = optionalId(request.eventId());
        EventIdentity identity = eventId == null ? null : repository.findEventIdentity(eventId);
        if (identity == null) {
            eventId = eventId == null ? id() : eventId;
            identity = new EventIdentity(eventId, building, point, type.name(), userId, now);
            try {
                repository.insertEventIdentity(identity);
            } catch (DuplicateKeyException exception) {
                versionConflict();
            }
        } else if (!Objects.equals(building, identity.buildingId())
                || !Objects.equals(point, identity.meterPointId())
                || !Objects.equals(type.name(), identity.eventType())) {
            versionConflict();
        }
        EventVersionRow row = new EventVersionRow(eventId, id(), repository.nextEventVersion(eventId),
                building, point, type.name(), local(request.occurredAt()), clean(request.preEventReading()),
                clean(request.postEventReading()), clean(request.rolloverModulus()),
                optionalText(request.oldMeterId(), 64), optionalText(request.newMeterId(), 64),
                optionalText(request.relationVersionBefore(), 32),
                optionalText(request.relationVersionAfter(), 32), "PENDING_REVIEW", source,
                text(request.evidenceReference(), 500, "计量事件依据无效"), simulation, 0,
                userId, now, null, null, null);
        try {
            repository.insertEventVersion(row);
        } catch (DuplicateKeyException exception) {
            versionConflict();
        }
        audit(userId, "CREATE_METER_EVENT_VERSION", "ENERGY_METER_EVENT", eventId,
                row.eventVersionId(), building, "eventType=" + type + ";status=PENDING_REVIEW", false);
        return eventView(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public MeterEventVersionView approveEvent(
            long userId, Collection<String> roles, String versionId, ApproveEvidenceRequest request) {
        authorization.requireReviewer(userId, roles);
        EventVersionRow row = requireEvent(versionId);
        authorization.checkBuilding(userId, roles, row.buildingId());
        requirePending(row.status());
        authorization.requireSeparation(row.createdBy(), userId);
        EventVersionRow previous = repository.findLatestApprovedEvent(row.eventId());
        if (previous != null) repository.disableEventVersion(previous.eventVersionId());
        if (repository.approveEvent(versionId, request.expectedRevision(), userId,
                LocalDateTime.now(), text(request.reviewComment(), 500, "审核意见无效")) != 1) {
            versionConflict();
        }
        EventVersionRow approved = requireEvent(versionId);
        audit(userId, "APPROVE_METER_EVENT_VERSION", "ENERGY_METER_EVENT", approved.eventId(),
                versionId, approved.buildingId(), "status=APPROVED", self(row.createdBy(), userId));
        return eventView(approved);
    }

    public List<CorrectionVersionView> listCorrections(
            long userId, Collection<String> roles, String buildingId, String pointId) {
        read(userId, roles, buildingId);
        return repository.listCorrectionVersions(required(buildingId, "建筑不能为空"),
                required(pointId, "测点不能为空")).stream().map(this::correctionView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public CorrectionVersionView createCorrection(
            long userId, Collection<String> roles, CreateCorrectionVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String building = required(request.buildingId(), "建筑不能为空");
        String point = required(request.meterPointId(), "测点不能为空");
        authorization.checkBuilding(userId, roles, building);
        String source = allowed(request.sourceType(), EVENT_SOURCES, "修正来源无效");
        String fact = text(request.originalFactIdentity(), 160, "原始事实身份无效");
        validateFactIdentity(point, fact);
        LocalDateTime now = LocalDateTime.now();
        String correctionId = optionalId(request.correctionId());
        CorrectionIdentity identity = correctionId == null
                ? null : repository.findCorrectionIdentity(correctionId);
        if (identity == null) {
            correctionId = correctionId == null ? id() : correctionId;
            identity = new CorrectionIdentity(correctionId, building, point, fact, userId, now);
            try {
                repository.insertCorrectionIdentity(identity);
            } catch (DuplicateKeyException exception) {
                versionConflict();
            }
        } else if (!Objects.equals(building, identity.buildingId())
                || !Objects.equals(point, identity.meterPointId())
                || !Objects.equals(fact, identity.originalFactIdentity())) {
            versionConflict();
        }
        CorrectionVersionRow row = new CorrectionVersionRow(correctionId, id(),
                repository.nextCorrectionVersion(correctionId), building, point, fact,
                requiredDecimal(request.originalValue(), "原始值不能为空"),
                requiredDecimal(request.correctedValue(), "修正值不能为空"),
                text(request.correctionReason(), 500, "修正原因无效"), "PENDING_REVIEW", source,
                text(request.evidenceReference(), 500, "修正依据无效"), false, "PENDING", 0,
                userId, now, null, null, null);
        try {
            repository.insertCorrectionVersion(row);
        } catch (DuplicateKeyException exception) {
            versionConflict();
        }
        audit(userId, "CREATE_ACTIVITY_CORRECTION_VERSION", "ENERGY_ACTIVITY_CORRECTION",
                correctionId, row.correctionVersionId(), building,
                "factIdentity=" + fact + ";status=PENDING_REVIEW", false);
        return correctionView(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public CorrectionVersionView approveCorrection(
            long userId, Collection<String> roles, String versionId, ApproveEvidenceRequest request) {
        authorization.requireReviewer(userId, roles);
        CorrectionVersionRow row = requireCorrection(versionId);
        authorization.checkBuilding(userId, roles, row.buildingId());
        requirePending(row.status());
        authorization.requireSeparation(row.createdBy(), userId);
        RawActivityDataView original = requireOriginalFact(userId, roles, row);
        if (BigDecimal.valueOf(original.rawValue()).compareTo(row.originalValue()) != 0) {
            throw error(CORRECTION_CONFLICT, "待审核修正的原始值与活动事实不一致");
        }
        String qualityVersion = original.policySource() + ":" + original.policyVersion()
                + ":" + original.policyConfigRevision();
        CorrectionVersionRow previous = repository.findLatestApprovedCorrection(row.correctionId());
        if (previous != null) repository.disableCorrectionVersion(previous.correctionVersionId());
        if (repository.approveCorrection(versionId, request.expectedRevision(), userId,
                LocalDateTime.now(), text(request.reviewComment(), 500, "审核意见无效"),
                qualityVersion) != 1) {
            versionConflict();
        }
        CorrectionVersionRow approved = requireCorrection(versionId);
        audit(userId, "APPROVE_ACTIVITY_CORRECTION_VERSION", "ENERGY_ACTIVITY_CORRECTION",
                approved.correctionId(), versionId, approved.buildingId(),
                "status=APPROVED;qualityPolicyVersion=" + qualityVersion,
                self(row.createdBy(), userId));
        return correctionView(approved);
    }

    public List<IntegrationPolicyVersionView> listPolicies(
            long userId, Collection<String> roles, String buildingId, String pointId) {
        read(userId, roles, buildingId);
        return repository.listPolicyVersions(required(buildingId, "建筑不能为空"),
                required(pointId, "测点不能为空")).stream().map(this::policyView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public IntegrationPolicyVersionView createPolicy(
            long userId, Collection<String> roles, CreateIntegrationPolicyVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String building = required(request.buildingId(), "建筑不能为空");
        String point = required(request.meterPointId(), "测点不能为空");
        authorization.checkBuilding(userId, roles, building);
        IntegrationMethod method = parse(IntegrationMethod.class, request.integrationMethod(),
                "积分方法无效");
        BoundaryHandling boundary = parse(BoundaryHandling.class, request.boundaryHandling(),
                "边界处理方式无效");
        String source = allowed(request.sourceType(), POLICY_SOURCES, "积分策略来源无效");
        if (request.maximumGapSeconds() == null || request.maximumGapSeconds() <= 0
                || request.minimumCoverageRatio() == null
                || request.minimumCoverageRatio().signum() < 0
                || request.minimumCoverageRatio().compareTo(BigDecimal.ONE) > 0) {
            validation("积分间隔或最低覆盖率无效");
        }
        if (request.effectiveFrom() == null
                || request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            validation("积分策略生效区间无效");
        }
        LocalDateTime now = LocalDateTime.now();
        PolicyIdentity identity = repository.findPolicyIdentity(building, point);
        if (identity == null) {
            identity = new PolicyIdentity(id(), building, point, userId, now);
            try {
                repository.insertPolicyIdentity(identity);
            } catch (DuplicateKeyException exception) {
                versionConflict();
            }
        }
        PolicyVersionRow row = new PolicyVersionRow(identity.policyId(), id(),
                repository.nextPolicyVersion(identity.policyId()), building, point, method.name(),
                request.maximumGapSeconds(), request.minimumCoverageRatio(), boundary.name(),
                "PENDING_REVIEW", source, text(request.evidenceReference(), 500, "积分策略依据无效"),
                request.effectiveFrom(), request.effectiveTo(), 0, userId, now, null, null, null);
        try {
            repository.insertPolicyVersion(row);
        } catch (DuplicateKeyException exception) {
            versionConflict();
        }
        audit(userId, "CREATE_INTEGRATION_POLICY_VERSION", "ENERGY_INTEGRATION_POLICY",
                identity.policyId(), row.policyVersionId(), building,
                "method=" + method + ";status=PENDING_REVIEW", false);
        return policyView(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public IntegrationPolicyVersionView approvePolicy(
            long userId, Collection<String> roles, String versionId, ApproveEvidenceRequest request) {
        authorization.requireReviewer(userId, roles);
        PolicyVersionRow row = requirePolicy(versionId);
        authorization.checkBuilding(userId, roles, row.buildingId());
        requirePending(row.status());
        authorization.requireSeparation(row.createdBy(), userId);
        PolicyVersionRow previous = repository.findLatestApprovedPolicy(row.policyId());
        if (previous != null) {
            if (!row.effectiveFrom().isAfter(previous.effectiveFrom())) {
                versionConflict();
            }
            repository.closePolicyVersion(previous.policyVersionId(), row.effectiveFrom());
        }
        if (repository.approvePolicy(versionId, request.expectedRevision(), userId,
                LocalDateTime.now(), text(request.reviewComment(), 500, "审核意见无效")) != 1) {
            versionConflict();
        }
        PolicyVersionRow approved = requirePolicy(versionId);
        audit(userId, "APPROVE_INTEGRATION_POLICY_VERSION", "ENERGY_INTEGRATION_POLICY",
                approved.policyId(), versionId, approved.buildingId(), "status=APPROVED",
                self(row.createdBy(), userId));
        return policyView(approved);
    }

    List<MeterEventEvidence> approvedEvents(String buildingId, String pointId, Instant from, Instant to) {
        return repository.listApprovedEvents(buildingId, pointId, local(from), local(to)).stream()
                .map(row -> new MeterEventEvidence(row.eventId(), row.eventVersionId(), row.buildingId(),
                        row.meterPointId(), MeterEventType.valueOf(row.eventType()), instant(row.occurredAt()),
                        EvidenceStatus.APPROVED, row.preEventReading(), row.postEventReading(),
                        row.rolloverModulus(), row.oldMeterId(), row.newMeterId(),
                        row.relationVersionBefore(), row.relationVersionAfter(), row.evidenceReference(),
                        row.createdBy(), row.approvedBy(), row.simulationFlag())).toList();
    }

    List<CorrectionEvidence> approvedCorrections(String buildingId, String pointId) {
        return repository.listApprovedCorrections(buildingId, pointId).stream()
                .map(row -> new CorrectionEvidence(row.correctionId(), row.correctionVersionId(),
                        row.originalFactIdentity(), row.originalValue(), row.correctedValue(),
                        row.correctionReason(), EvidenceStatus.APPROVED, row.evidenceReference(),
                        row.createdBy(), row.approvedBy(), row.qualityGatePassed())).toList();
    }

    IntegrationPolicy effectivePolicy(String buildingId, String pointId, Instant at) {
        PolicyVersionRow row = repository.findEffectivePolicy(buildingId, pointId, local(at));
        return row == null ? null : new IntegrationPolicy(row.policyVersionId(),
                IntegrationMethod.valueOf(row.integrationMethod()), row.maximumGapSeconds(),
                row.minimumCoverageRatio(), BoundaryHandling.valueOf(row.boundaryHandling()),
                EvidenceStatus.APPROVED);
    }

    private RawActivityDataView requireOriginalFact(
            long userId, Collection<String> roles, CorrectionVersionRow row) {
        long eventTime = validateFactIdentity(row.meterPointId(), row.originalFactIdentity());
        AggregationActivitySnapshot snapshot = activityDataService.aggregationSnapshot(userId, roles,
                row.buildingId(), row.meterPointId(), eventTime, Math.addExact(eventTime, 1),
                Instant.now().toEpochMilli());
        return snapshot.items().stream().filter(value -> value.eventTime() == eventTime)
                .findFirst().orElseThrow(() -> error(CORRECTION_CONFLICT,
                        "原始事实不存在或未通过聚合质量门禁"));
    }

    private void read(long userId, Collection<String> roles, String buildingId) {
        authorization.requireReader(roles);
        authorization.checkBuilding(userId, roles, required(buildingId, "建筑不能为空"));
    }

    private EventVersionRow requireEvent(String id) {
        EventVersionRow row = repository.findEventVersion(required(id, "事件版本不能为空"));
        if (row == null) throw error(404, NOT_FOUND, "计量事件版本不存在");
        return row;
    }

    private CorrectionVersionRow requireCorrection(String id) {
        CorrectionVersionRow row = repository.findCorrectionVersion(required(id, "修正版本不能为空"));
        if (row == null) throw error(404, NOT_FOUND, "活动修正版本不存在");
        return row;
    }

    private PolicyVersionRow requirePolicy(String id) {
        PolicyVersionRow row = repository.findPolicyVersion(required(id, "策略版本不能为空"));
        if (row == null) throw error(404, NOT_FOUND, "积分策略版本不存在");
        return row;
    }

    private static void validateEvent(MeterEventType type, CreateMeterEventVersionRequest request) {
        switch (type) {
            case RESET -> requireReadings(request, "复位事件必须提供复位前后读数");
            case ROLLOVER -> {
                if (request.rolloverModulus() == null || request.rolloverModulus().signum() <= 0) {
                    validation("回绕事件必须提供大于零的计数周期");
                }
            }
            case REPLACEMENT -> {
                requireReadings(request, "换表事件必须提供旧表末值和新表初值");
                if (blank(request.oldMeterId()) || blank(request.newMeterId())
                        || blank(request.relationVersionBefore()) || blank(request.relationVersionAfter())) {
                    validation("换表事件必须提供新旧表身份和前后关系版本");
                }
            }
            case DATA_ERROR -> { }
        }
    }

    private static void requireReadings(CreateMeterEventVersionRequest request, String message) {
        if (request.preEventReading() == null || request.postEventReading() == null
                || request.preEventReading().signum() < 0 || request.postEventReading().signum() < 0) {
            validation(message);
        }
    }

    private static long validateFactIdentity(String pointId, String identity) {
        int separator = identity == null ? -1 : identity.lastIndexOf('@');
        if (separator <= 0 || !identity.substring(0, separator).equals(pointId)) {
            validation("原始事实身份必须使用 pointId@eventTime");
        }
        try {
            return Long.parseLong(identity.substring(separator + 1));
        } catch (NumberFormatException exception) {
            validation("原始事实身份必须使用 pointId@eventTime");
            return -1;
        }
    }

    private MeterEventVersionView eventView(EventVersionRow row) {
        return new MeterEventVersionView(row.eventId(), row.eventVersionId(), row.versionNo(),
                row.buildingId(), row.meterPointId(), row.eventType(), instant(row.occurredAt()),
                row.preEventReading(), row.postEventReading(), row.rolloverModulus(), row.oldMeterId(),
                row.newMeterId(), row.relationVersionBefore(), row.relationVersionAfter(), row.status(),
                row.sourceType(), row.evidenceReference(), row.simulationFlag(), row.configRevision(),
                row.createdBy(), row.createdAt(), row.approvedBy(), row.approvedAt(), row.reviewComment());
    }

    private CorrectionVersionView correctionView(CorrectionVersionRow row) {
        return new CorrectionVersionView(row.correctionId(), row.correctionVersionId(), row.versionNo(),
                row.buildingId(), row.meterPointId(), row.originalFactIdentity(), row.originalValue(),
                row.correctedValue(), row.correctionReason(), row.status(), row.sourceType(),
                row.evidenceReference(), row.qualityGatePassed(), row.qualityPolicyVersion(),
                row.configRevision(), row.createdBy(), row.createdAt(), row.approvedBy(),
                row.approvedAt(), row.reviewComment());
    }

    private IntegrationPolicyVersionView policyView(PolicyVersionRow row) {
        return new IntegrationPolicyVersionView(row.policyId(), row.policyVersionId(), row.versionNo(),
                row.buildingId(), row.meterPointId(), row.integrationMethod(), row.maximumGapSeconds(),
                row.minimumCoverageRatio(), row.boundaryHandling(), row.status(), row.sourceType(),
                row.evidenceReference(), row.effectiveFrom(), row.effectiveTo(), row.configRevision(),
                row.createdBy(), row.createdAt(), row.approvedBy(), row.approvedAt(), row.reviewComment());
    }

    private void audit(long operatorId, String action, String objectType, String objectId,
                       String versionId, String buildingId, String after, boolean selfApproval) {
        auditWriter.append(new AuditEvidence("ENERGY_AGGREGATION", buildingId, "USER", operatorId,
                action, objectType, objectId, versionId, null, null, after, "SUCCESS", null,
                TraceContext.current(), LocalDateTime.now(), auditProperties.getEnvironmentMode(), selfApproval));
    }

    private static boolean self(long creator, long reviewer) {
        return creator == reviewer;
    }

    private static void requirePending(String status) {
        if (!"PENDING_REVIEW".equals(status)) {
            throw error(STATUS_CONFLICT, "只有待审核版本可以审核");
        }
    }

    private static String allowed(String value, Set<String> values, String message) {
        String normalized = required(value, message).toUpperCase(Locale.ROOT);
        if (!values.contains(normalized)) validation(message);
        return normalized;
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw error(400, VALIDATION_FAILED, message);
        }
    }

    private static String optionalId(String value) {
        if (blank(value)) return null;
        String id = value.trim();
        if (!id.matches("[A-Za-z0-9_-]{1,32}")) validation("稳定身份无效");
        return id;
    }

    private static String required(String value, String message) {
        if (blank(value)) validation(message);
        return value.trim();
    }

    private static String text(String value, int max, String message) {
        String result = required(value, message);
        if (result.length() > max) validation(message);
        return result;
    }

    private static String optionalText(String value, int max) {
        if (blank(value)) return null;
        String result = value.trim();
        if (result.length() > max) validation("可选证据字段过长");
        return result;
    }

    private static BigDecimal requiredDecimal(BigDecimal value, String message) {
        if (value == null) validation(message);
        return clean(value);
    }

    private static BigDecimal clean(BigDecimal value) {
        if (value == null) return null;
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void validation(String message) {
        throw error(400, VALIDATION_FAILED, message);
    }

    private static void versionConflict() {
        throw error(VERSION_CONFLICT, "版本已变化或稳定身份与请求不一致");
    }
}
