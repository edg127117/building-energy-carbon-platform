package com.platform.energy.period;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.energy.period.EnergyPeriodModels.ExceptionPolicyVersion;
import com.platform.energy.period.EnergyPeriodModels.LockAction;
import com.platform.energy.period.EnergyPeriodModels.PeriodPolicyVersion;
import com.platform.energy.period.api.EnergyPeriodContracts.ApproveRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.CreateExceptionPolicyRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.CreatePeriodPolicyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.platform.energy.period.EnergyPeriodErrors.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 治理版本化研发周期口径和封账例外白名单，不发布生产规则。 */
public class EnergyPeriodGovernanceService {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final EnergyPeriodAuthorization authorization;
    private final EnergyPeriodGovernanceRepository repository;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    @Transactional(rollbackFor = Exception.class)
    public PeriodPolicyVersion createPeriodPolicy(
            long userId, Collection<String> roles, CreatePeriodPolicyRequest request) {
        authorization.requirePolicyMaintainer(userId, roles);
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.checkBuilding(userId, roles, buildingId);
        String timezoneId = timezone(request.timezoneId());
        if (request.closingDelayHours() == null || request.closingDelayHours() < 0
                || request.closingDelayHours() > 720) {
            validation("封账延迟小时必须在0到720之间");
        }
        if (!"REVIEW_REQUIRED".equals(normalize(request.lockMode()))) {
            validation("首版封账模式必须是REVIEW_REQUIRED");
        }
        if (!"SIMULATION".equals(normalize(request.sourceType()))) {
            validation("研发周期口径来源必须标记为SIMULATION");
        }
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        LocalDateTime now = LocalDateTime.now();
        String policyId = repository.findPeriodPolicyId(buildingId);
        if (policyId == null) {
            policyId = id();
            try {
                repository.insertPeriodPolicyIdentity(policyId, buildingId, userId, now);
            } catch (DuplicateKeyException exception) {
                versionConflict();
            }
        }
        PeriodPolicyVersion value = new PeriodPolicyVersion(policyId, id(),
                repository.nextPeriodPolicyVersion(policyId), buildingId, timezoneId,
                request.closingDelayHours(), "REVIEW_REQUIRED", "PENDING_REVIEW", "SIMULATION",
                text(request.evidenceReference(), 500, "证据引用无效"), range.from(), range.to(),
                0, userId, now, null, null);
        repository.insertPeriodPolicyVersion(value);
        audit(userId, buildingId, "CREATE_PERIOD_POLICY_VERSION", "ENERGY_PERIOD_POLICY",
                value.policyId(), value.versionId(), null, summary(value), false);
        return value;
    }

    @Transactional(rollbackFor = Exception.class)
    public PeriodPolicyVersion approvePeriodPolicy(
            long userId, Collection<String> roles, String versionId, ApproveRequest request) {
        authorization.requirePolicyReviewer(userId, roles);
        PeriodPolicyVersion value = requirePeriodPolicy(versionId);
        authorization.checkBuilding(userId, roles, value.buildingId());
        authorization.requireSeparation(value.createdBy(), userId);
        requirePending(value.status());
        PeriodPolicyVersion previous = repository.findLatestApprovedPeriodPolicy(value.policyId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closePeriodPolicy);
        if (repository.approvePeriodPolicy(value.versionId(), request.expectedRevision(), userId,
                LocalDateTime.now(), text(request.reviewComment(), 500, "审核意见无效")) != 1) {
            versionConflict();
        }
        PeriodPolicyVersion approved = requirePeriodPolicy(versionId);
        audit(userId, approved.buildingId(), "APPROVE_PERIOD_POLICY_VERSION",
                "ENERGY_PERIOD_POLICY", approved.policyId(), approved.versionId(), summary(value),
                summary(approved), selfApproval(value.createdBy(), userId));
        return approved;
    }

    @Transactional(rollbackFor = Exception.class)
    public ExceptionPolicyVersion createExceptionPolicy(
            long userId, Collection<String> roles, CreateExceptionPolicyRequest request) {
        authorization.requirePolicyMaintainer(userId, roles);
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.checkBuilding(userId, roles, buildingId);
        String issueCode = code(request.issueCode(), "问题编码无效");
        String scope = text(request.applicableScope(), 64, "适用范围无效").toUpperCase(Locale.ROOT);
        LockAction action = parse(LockAction.class, request.lockAction(), "封账动作无效");
        ratio(request.maximumAffectedRatio(), "最大影响比例无效");
        ratio(request.minimumCoverageRatio(), "最低覆盖率无效");
        if (request.maximumAffectedCount() != null && request.maximumAffectedCount() < 0) {
            validation("最大影响数量不能为负数");
        }
        if (!"SIMULATION".equals(normalize(request.sourceType()))) {
            validation("研发例外策略来源必须标记为SIMULATION");
        }
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        LocalDateTime now = LocalDateTime.now();
        String policyId = repository.findExceptionPolicyId(buildingId, issueCode, scope);
        if (policyId == null) {
            policyId = id();
            try {
                repository.insertExceptionPolicyIdentity(policyId, buildingId, issueCode, scope,
                        userId, now);
            } catch (DuplicateKeyException exception) {
                versionConflict();
            }
        }
        ExceptionPolicyVersion value = new ExceptionPolicyVersion(policyId, id(),
                repository.nextExceptionPolicyVersion(policyId), buildingId, issueCode,
                text(request.severity(), 16, "严重级别无效").toUpperCase(Locale.ROOT), action.name(),
                request.maximumAffectedCount(), normalized(request.maximumAffectedRatio()),
                normalized(request.minimumCoverageRatio()), scope,
                Boolean.TRUE.equals(request.requiresApproval()),
                text(request.requiredEvidence(), 500, "必要证据说明无效"), "PENDING_REVIEW",
                "SIMULATION", text(request.evidenceReference(), 500, "证据引用无效"),
                range.from(), range.to(), 0, userId, now, null, null);
        repository.insertExceptionPolicyVersion(value);
        audit(userId, buildingId, "CREATE_LOCK_EXCEPTION_POLICY_VERSION",
                "ENERGY_LOCK_EXCEPTION_POLICY", value.policyId(), value.versionId(), null,
                summary(value), false);
        return value;
    }

    @Transactional(rollbackFor = Exception.class)
    public ExceptionPolicyVersion approveExceptionPolicy(
            long userId, Collection<String> roles, String versionId, ApproveRequest request) {
        authorization.requireExceptionReviewer(userId, roles);
        ExceptionPolicyVersion value = requireExceptionPolicy(versionId);
        authorization.checkBuilding(userId, roles, value.buildingId());
        authorization.requireSeparation(value.createdBy(), userId);
        requirePending(value.status());
        ExceptionPolicyVersion previous = repository.findLatestApprovedExceptionPolicy(value.policyId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeExceptionPolicy);
        if (repository.approveExceptionPolicy(value.versionId(), request.expectedRevision(), userId,
                LocalDateTime.now(), text(request.reviewComment(), 500, "审核意见无效")) != 1) {
            versionConflict();
        }
        ExceptionPolicyVersion approved = requireExceptionPolicy(versionId);
        audit(userId, approved.buildingId(), "APPROVE_LOCK_EXCEPTION_POLICY_VERSION",
                "ENERGY_LOCK_EXCEPTION_POLICY", approved.policyId(), approved.versionId(),
                summary(value), summary(approved), selfApproval(value.createdBy(), userId));
        return approved;
    }

    public PeriodPolicyVersion effectivePolicy(String buildingId, LocalDateTime at) {
        PeriodPolicyVersion value = repository.findEffectivePeriodPolicy(buildingId, at);
        if (value == null) throw error(409, POLICY_REQUIRED, "指定周期缺少已审核周期口径");
        return value;
    }

    public PeriodPolicyVersion periodPolicyVersion(String versionId) {
        return requirePeriodPolicy(versionId);
    }

    public List<ExceptionPolicyVersion> effectiveExceptionPolicies(
            String buildingId, List<String> issueCodes, LocalDateTime at) {
        return repository.findEffectiveExceptionPolicies(buildingId, issueCodes, at);
    }

    private PeriodPolicyVersion requirePeriodPolicy(String versionId) {
        PeriodPolicyVersion value = repository.findPeriodPolicyVersion(text(versionId, 32, "版本无效"));
        if (value == null) throw error(404, NOT_FOUND, "周期口径版本不存在");
        return value;
    }

    private ExceptionPolicyVersion requireExceptionPolicy(String versionId) {
        ExceptionPolicyVersion value = repository.findExceptionPolicyVersion(text(versionId, 32, "版本无效"));
        if (value == null) throw error(404, NOT_FOUND, "封账例外策略版本不存在");
        return value;
    }

    private void audit(long userId, String buildingId, String action, String objectType,
                       String objectId, String versionId, String before, String after,
                       boolean selfApproval) {
        auditWriter.append(new AuditEvidence("ENERGY_PERIOD", buildingId, "USER", userId,
                action, objectType, objectId, versionId, null, before, after, "SUCCESS", null,
                TraceContext.current(), LocalDateTime.now(), auditProperties.getEnvironmentMode(),
                selfApproval));
    }

    private static String summary(PeriodPolicyVersion value) {
        return "version=" + value.versionNo() + ";timezone=" + value.timezoneId()
                + ";closingDelayHours=" + value.closingDelayHours() + ";status=" + value.status();
    }

    private static String summary(ExceptionPolicyVersion value) {
        return "version=" + value.versionNo() + ";issue=" + value.issueCode()
                + ";action=" + value.lockAction() + ";status=" + value.status();
    }

    private boolean selfApproval(long creatorId, long reviewerId) {
        return creatorId == reviewerId && auditProperties.isAllowSelfApproval();
    }

    private static void closePrevious(String versionId, LocalDateTime previousFrom,
                                      LocalDateTime nextFrom, VersionCloser closer) {
        if (versionId == null) return;
        if (!nextFrom.isAfter(previousFrom)) validation("新审核版本必须晚于当前已审核版本生效");
        closer.close(versionId, nextFrom);
    }

    private static TimeRange range(LocalDateTime from, LocalDateTime to) {
        if (from == null || (to != null && !to.isAfter(from))) validation("业务有效期无效");
        return new TimeRange(from, to);
    }

    private static String timezone(String value) {
        try {
            return ZoneId.of(text(value, 64, "时区无效")).getId();
        } catch (DateTimeException exception) {
            validation("时区无效");
            return null;
        }
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static void ratio(BigDecimal value, String message) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            validation(message);
        }
    }

    private static String code(String value, String message) {
        String normalized = normalize(value);
        if (!CODE.matcher(normalized).matches()) validation(message);
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(String value, int max, String message) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > max) validation(message);
        return normalized;
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, normalize(value));
        } catch (IllegalArgumentException exception) {
            throw error(400, VALIDATION_FAILED, message);
        }
    }

    private static void requirePending(String status) {
        if (!"PENDING_REVIEW".equals(status)) {
            throw error(409, STATUS_CONFLICT, "只有待审核版本可以审核");
        }
    }

    private static void validation(String message) {
        throw error(400, VALIDATION_FAILED, message);
    }

    private static void versionConflict() {
        throw error(409, VERSION_CONFLICT, "版本已被其他操作修改");
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record TimeRange(LocalDateTime from, LocalDateTime to) {
    }

    @FunctionalInterface
    private interface VersionCloser {
        void close(String versionId, LocalDateTime effectiveTo);
    }
}
