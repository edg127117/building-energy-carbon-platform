package com.platform.carbon;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.api.CarbonContracts.RunCalculationRequest;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.platform.carbon.CarbonErrors.*;

@Service
@RequiredArgsConstructor
/** 编排普通同步计算和自动重算候选计算，计算期间不持有 MySQL 事务。 */
public class CarbonCalculationService {
    private final CarbonAuthorization authorization;
    private final CarbonActivityInputPort activityInputPort;
    private final CarbonRuleRepository ruleRepository;
    private final CarbonCalculationRepository repository;
    private final CarbonCalculationPersistence persistence;
    private final CarbonCalculationCore core;
    private final CarbonProperties properties;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    public CalculationDetail run(long userId, Collection<String> roles,
                                 RunCalculationRequest request) {
        PeriodType periodType = enumValue(PeriodType.class, request.periodType(), "周期类型无效");
        ResultNature nature = enumValue(ResultNature.class, request.resultNature(), "结果性质无效");
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.requireCalculationRunner(userId, roles, buildingId);
        PeriodWindow window = period(periodType, request.startInclusive(), request.endExclusive(),
                request.timezoneId());
        return execute(userId, buildingId, periodType, window, nature,
                text(request.idempotencyKey(), 100, "幂等键无效"), null, false);
    }

    CalculationDetail runCandidate(String buildingId, int accountingYear, ResultNature nature,
                                   String oldBatchId, String idempotencyKey) {
        ZoneId zone = ZoneId.of(resolveTimezone(oldBatchId));
        Instant start = LocalDate.of(accountingYear, 1, 1).atStartOfDay(zone).toInstant();
        Instant end = LocalDate.of(accountingYear + 1, 1, 1).atStartOfDay(zone).toInstant();
        return execute(0L, buildingId, PeriodType.YEAR,
                new PeriodWindow(start, end, zone.getId()), nature,
                idempotencyKey, oldBatchId, true);
    }

    public CalculationDetail detail(long userId, Collection<String> roles, String batchId) {
        CalculationDetail detail = repository.detail(text(batchId, 32, "计算批次标识无效"));
        if (detail == null) throw error(404, NOT_FOUND, "碳计算批次不存在");
        authorization.requireReader(userId, roles, detail.batch().buildingId());
        if (detail.batch().status().equals("CALCULATING")
                && LocalDateTime.now().isAfter(detail.batch().deadlineAt())) {
            persistence.timeout(detail.batch().batchId(), LocalDateTime.now());
            detail = repository.detail(batchId);
        }
        return detail;
    }

    CalculationBatch currentFormal(String buildingId, int year, String timezoneId) {
        ZoneId zone = ZoneId.of(timezoneId);
        return repository.findCurrentFormal(buildingId, PeriodType.YEAR,
                LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
                LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant());
    }

    private CalculationDetail execute(long actorId, String buildingId, PeriodType periodType,
                                      PeriodWindow window, ResultNature nature,
                                      String idempotencyKey, String supersedesBatchId,
                                      boolean recalculationCandidate) {
        String requestHash = CarbonCalculationCore.sha256(buildingId + '|' + periodType + '|'
                + window.start() + '|' + window.end() + '|' + window.timezoneId() + '|'
                + nature + '|' + supersedesBatchId);
        CalculationBatch existing = repository.findByIdempotency(buildingId, idempotencyKey);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                throw error(409, IDEMPOTENCY_CONFLICT, "相同幂等键对应不同计算请求");
            }
            return repository.detail(existing.batchId());
        }
        if (nature == ResultNature.FORMAL && !recalculationCandidate
                && repository.findCurrentFormal(buildingId, periodType,
                window.start(), window.end()) != null) {
            throw error(409, STATUS_CONFLICT, "已有正式结果，必须通过重算审批替代");
        }
        String rounding = ruleRepository.activeRoundingPolicyId();
        if (rounding == null) throw error(503, DEPENDENCY_UNAVAILABLE, "舍入策略不可用");
        LocalDateTime started = LocalDateTime.now();
        String batchId = id();
        LocalDateTime deadline = started.plus(properties.getCalculationTimeout());
        String lock = buildingId + '|' + periodType + '|' + window.start() + '|'
                + window.end() + '|' + nature;
        CalculationBatch batch = new CalculationBatch(batchId, buildingId, periodType,
                window.start(), window.end(), window.timezoneId(), nature,
                recalculationCandidate ? "CANDIDATE" : "DIRECT", "CALCULATING",
                idempotencyKey, requestHash, lock, rounding, supersedesBatchId, started,
                deadline, null, null, 0, 0, false, null, null, actorId, started);
        try {
            persistence.create(batch);
        } catch (DuplicateKeyException exception) {
            CalculationBatch raced = repository.findByIdempotency(buildingId, idempotencyKey);
            if (raced != null && raced.requestHash().equals(requestHash)) {
                return repository.detail(raced.batchId());
            }
            throw error(409, CONCURRENT_CALCULATION,
                    "同一建筑、周期和核算口径已有计算正在执行");
        }
        audit(actorId, buildingId, "START_CARBON_CALCULATION", batchId, null,
                calculationSummary(batch), false);
        long startNanos = System.nanoTime();
        try {
            CalculationResult result = calculate(batch, window);
            long duration = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            LocalDateTime completed = LocalDateTime.now();
            if (completed.isAfter(deadline)) {
                persistence.timeout(batchId, completed);
                throw error(504, CALCULATION_TIMEOUT, "碳计算超过约定超时");
            }
            boolean slow = duration >= properties.getSlowCalculationThreshold().toMillis();
            persistence.complete(batchId, result,
                    result.items().size() + result.failures().size(), slow, duration, completed);
            CalculationDetail detail = repository.detail(batchId);
            audit(actorId, buildingId, result.complete()
                            ? "COMPLETE_CARBON_CALCULATION" : "INCOMPLETE_CARBON_CALCULATION",
                    batchId, calculationSummary(batch), calculationSummary(detail.batch()), false);
            return detail;
        } catch (RuntimeException exception) {
            long duration = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            String code = safeCode(exception);
            String message = safeMessage(exception);
            if (!CALCULATION_TIMEOUT.equals(code)) {
                persistence.fail(batchId, code, message, LocalDateTime.now(), duration);
            }
            audit(actorId, buildingId, "FAIL_CARBON_CALCULATION", batchId,
                    calculationSummary(batch), "status=FAILED;error=" + code, false);
            throw exception;
        }
    }

    private CalculationResult calculate(CalculationBatch batch, PeriodWindow window) {
        List<ActivitySegment> activities = activityInputPort.read(batch.buildingId(),
                batch.periodType(), window.start(), window.end(), properties.getMaximumSnapshots());
        if (activities.isEmpty()) {
            throw error(409, DEPENDENCY_UNAVAILABLE, "核算周期没有已封账的权威活动数据");
        }
        if (activities.size() > properties.getMaximumDetails()) {
            throw error(409, LIMIT_EXCEEDED, "计算明细超过单次硬上限");
        }
        String province = province(ruleRepository.findBuildingRegion(batch.buildingId()));
        List<CalculatedItem> items = new ArrayList<>();
        List<CalculationFailure> failures = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        for (ActivitySegment activity : activities) {
            validateActivity(batch, activity);
            boolean incompleteActivity = "INCOMPLETE".equals(activity.completeness())
                    || "LOCKED_PARTIAL".equals(activity.lockStatus());
            if (batch.resultNature() == ResultNature.FORMAL
                    && (activity.resultNature() != ResultNature.FORMAL || incompleteActivity)) {
                throw error(409, ACTIVITY_INCOMPLETE,
                        "正式计算要求全部活动快照正式且完整");
            }
            if (incompleteActivity) incomplete.add(activity.snapshotId() + ":活动数据不完整");
            try {
                LocalDateTime start = LocalDateTime.ofInstant(activity.startInclusive(),
                        ZoneId.of(activity.timezoneId()));
                LocalDateTime end = LocalDateTime.ofInstant(activity.endExclusive(),
                        ZoneId.of(activity.timezoneId()));
                FactorMatch match = core.match(activity, province, batch.resultNature(),
                        ruleRepository.findCandidateFactors(activity.energyItemCode(), start, end));
                GwpVersion gwp = gwp(match.factor(), batch.resultNature(), start, end);
                items.add(core.calculate(activity, match, gwp));
            } catch (BusinessException exception) {
                if (batch.resultNature() == ResultNature.FORMAL) throw exception;
                failures.add(new CalculationFailure(activity, safeCode(exception),
                        safeMessage(exception)));
                incomplete.add(activity.snapshotId() + ':' + safeCode(exception));
            }
        }
        DenominatorVersion area = null;
        DenominatorVersion population = null;
        if (batch.periodType() == PeriodType.YEAR) {
            LocalDate yearStart = LocalDateTime.ofInstant(window.start(),
                    ZoneId.of(window.timezoneId())).toLocalDate();
            LocalDate yearEnd = LocalDateTime.ofInstant(window.end(),
                    ZoneId.of(window.timezoneId())).toLocalDate();
            UsageNature nature = batch.resultNature() == ResultNature.FORMAL
                    ? UsageNature.FORMAL : UsageNature.DEVELOPMENT_REFERENCE;
            area = denominator(batch.buildingId(), DenominatorType.BUILDING_AREA,
                    nature, yearStart, yearEnd);
            population = denominator(batch.buildingId(), DenominatorType.RESIDENT_POPULATION,
                    nature, yearStart, yearEnd);
        }
        List<SummaryMetric> summaries = core.summarize(items, batch.periodType(), area, population);
        return new CalculationResult(items, failures, summaries,
                incomplete.isEmpty(), incomplete);
    }

    private DenominatorVersion denominator(String buildingId, DenominatorType type,
                                           UsageNature nature, LocalDate start, LocalDate end) {
        List<DenominatorVersion> values = ruleRepository.findActiveDenominators(
                buildingId, type, nature, start, end);
        if (values.size() > 1) {
            throw error(409, VERSION_CONFLICT, "核算年度匹配到多个分母版本");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private GwpVersion gwp(FactorVersion factor, ResultNature resultNature,
                           LocalDateTime start, LocalDateTime end) {
        if (!"GAS_MASS".equals(factor.resultBasis())) return null;
        UsageNature nature = resultNature == ResultNature.FORMAL
                ? UsageNature.FORMAL : UsageNature.DEVELOPMENT_REFERENCE;
        List<GwpVersion> values = ruleRepository.findActiveGwp(
                factor.gasCode(), nature, start, end);
        if (values.isEmpty()) throw error(409, FACTOR_MISSING, "缺少完整覆盖活动周期的GWP版本");
        if (values.size() != 1) throw error(409, FACTOR_CONFLICT, "活动周期匹配到多个GWP版本");
        return values.getFirst();
    }

    private static void validateActivity(CalculationBatch batch, ActivitySegment value) {
        if (!batch.buildingId().equals(value.buildingId())
                || value.startInclusive().isBefore(batch.periodStart())
                || value.endExclusive().isAfter(batch.periodEnd())
                || value.periodType() != batch.periodType()
                || !value.timezoneId().equals(batch.timezoneId())
                || !value.endExclusive().isAfter(value.startInclusive())
                || value.quantity() == null || value.quantity().signum() < 0) {
            throw error(409, DEPENDENCY_UNAVAILABLE, "活动数据越界或数值无效");
        }
    }

    private String resolveTimezone(String oldBatchId) {
        CalculationBatch old = repository.findBatch(oldBatchId);
        if (old == null || old.periodType() != PeriodType.YEAR) {
            throw error(409, RECALCULATION_CONFLICT, "重算缺少原年度结果或周期证据");
        }
        return old.timezoneId();
    }

    private static PeriodWindow period(PeriodType type, Instant start, Instant end,
                                       String timezoneId) {
        if (start == null || end == null || !end.isAfter(start)) validation("周期边界无效");
        ZoneId zone;
        try {
            zone = ZoneId.of(text(timezoneId, 64, "时区无效"));
        } catch (RuntimeException exception) {
            validation("时区无效");
            return null;
        }
        ZonedDateTime localStart = start.atZone(zone);
        ZonedDateTime expectedEnd;
        boolean aligned = localStart.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                && localStart.getDayOfMonth() == 1;
        if (type == PeriodType.YEAR) {
            aligned &= localStart.getMonthValue() == 1;
            expectedEnd = localStart.plusYears(1);
        } else if (type == PeriodType.QUARTER) {
            aligned &= Set.of(1, 4, 7, 10).contains(localStart.getMonthValue());
            expectedEnd = localStart.plusMonths(3);
        } else {
            expectedEnd = localStart.plusMonths(1);
        }
        if (!aligned || !expectedEnd.toInstant().equals(end)) {
            validation("周期必须是指定时区的完整自然月、季度或年度");
        }
        return new PeriodWindow(start, end, zone.getId());
    }

    private static String province(String region) {
        if (region == null || region.isBlank()) return null;
        String value = region.trim().toUpperCase(Locale.ROOT);
        return value.matches("[0-9]{6}") ? value.substring(0, 2) + "0000" : value;
    }

    private void audit(long actorId, String buildingId, String action, String batchId,
                       String before, String after, boolean selfApproval) {
        auditWriter.append(new AuditEvidence("CARBON_MANAGEMENT", buildingId,
                actorId == 0 ? "SYSTEM" : "USER", actorId == 0 ? null : actorId,
                action, "CARBON_CALCULATION_BATCH", batchId, null, null, before, after,
                "SUCCESS", null, TraceContext.current(), LocalDateTime.now(),
                auditProperties.getEnvironmentMode(), selfApproval));
    }

    private static String calculationSummary(CalculationBatch value) {
        return "period=" + value.periodType() + ";start=" + value.periodStart()
                + ";nature=" + value.resultNature() + ";status=" + value.status()
                + ";snapshots=" + value.snapshotCount() + ";details=" + value.detailCount();
    }

    private static String safeCode(Throwable failure) {
        return failure instanceof BusinessException business && business.getErrorCode() != null
                ? business.getErrorCode() : "CARBON_CALCULATION_FAILED";
    }

    private static String safeMessage(Throwable failure) {
        if (!(failure instanceof BusinessException)) return "碳计算执行失败";
        String value = failure.getMessage();
        if (value == null || value.isBlank()) return "计算失败";
        return value.substring(0, Math.min(500, value.length()));
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            validation(message);
            return null;
        }
    }

    private static String text(String value, int max, String message) {
        if (value == null || value.isBlank() || value.trim().length() > max) validation(message);
        return value.trim();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void validation(String message) {
        throw error(400, VALIDATION_FAILED, message);
    }

    private record PeriodWindow(Instant start, Instant end, String timezoneId) {
    }
}
