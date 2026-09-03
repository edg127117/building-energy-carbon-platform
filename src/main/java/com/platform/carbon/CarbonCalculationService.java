package com.platform.carbon;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.CarbonCalculationCore.DenominatorSelection;
import com.platform.carbon.api.CarbonContracts.RunCalculationRequest;
import com.platform.framework.exception.BusinessException;
import org.slf4j.MDC;
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
import java.util.function.Supplier;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.platform.carbon.CarbonErrors.*;

@Service
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
    private final Semaphore inFlightCalculations;

    public CarbonCalculationService(CarbonAuthorization authorization,
                                    CarbonActivityInputPort activityInputPort,
                                    CarbonRuleRepository ruleRepository,
                                    CarbonCalculationRepository repository,
                                    CarbonCalculationPersistence persistence,
                                    CarbonCalculationCore core,
                                    CarbonProperties properties,
                                    AuditEvidenceWriter auditWriter,
                                    AuditGovernanceProperties auditProperties) {
        this.authorization = authorization;
        this.activityInputPort = activityInputPort;
        this.ruleRepository = ruleRepository;
        this.repository = repository;
        this.persistence = persistence;
        this.core = core;
        this.properties = properties;
        this.auditWriter = auditWriter;
        this.auditProperties = auditProperties;
        this.inFlightCalculations = new Semaphore(properties.getMaximumConcurrentCalculations(), true);
    }

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
            recoverTimedOutBatch(detail.batch().batchId());
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
        CarbonCalculationDeadline deadline = CarbonCalculationDeadline.start(
                properties.getCalculationTimeout());
        String requestHash = CarbonCalculationCore.sha256(buildingId + '|' + periodType + '|'
                + window.start() + '|' + window.end() + '|' + window.timezoneId() + '|'
                + nature + '|' + supersedesBatchId);
        CalculationBatch existing = boundedRead(deadline,
                () -> repository.findByIdempotency(buildingId, idempotencyKey));
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                throw error(409, IDEMPOTENCY_CONFLICT, "相同幂等键对应不同计算请求");
            }
            if ("CALCULATING".equals(existing.status())
                    && !existing.deadlineAt().isAfter(LocalDateTime.now())) {
                recoverTimedOutBatch(existing.batchId());
            }
            return boundedRead(deadline, () -> repository.detail(existing.batchId()));
        }
        if (nature == ResultNature.FORMAL && !recalculationCandidate
                && boundedRead(deadline, () -> repository.findCurrentFormal(buildingId, periodType,
                window.start(), window.end())) != null) {
            throw error(409, STATUS_CONFLICT, "已有正式结果，必须通过重算审批替代");
        }
        String rounding = boundedRead(deadline, ruleRepository::activeRoundingPolicyId);
        if (rounding == null) throw error(503, DEPENDENCY_UNAVAILABLE, "舍入策略不可用");
        LocalDateTime started = LocalDateTime.now();
        String batchId = id();
        String lock = buildingId + '|' + periodType + '|' + window.start() + '|'
                + window.end() + '|' + nature;
        CalculationBatch batch = new CalculationBatch(batchId, buildingId, periodType,
                window.start(), window.end(), window.timezoneId(), nature,
                recalculationCandidate ? "CANDIDATE" : "DIRECT", "CALCULATING",
                idempotencyKey, requestHash, lock, rounding, supersedesBatchId, started,
                deadline.deadlineAt(), null, null, 0, 0, false, null, null, actorId, started);
        try {
            create(deadline, batch);
        } catch (DuplicateKeyException exception) {
            CalculationBatch raced = boundedRead(deadline,
                    () -> repository.findByIdempotency(buildingId, idempotencyKey));
            if (raced != null && raced.requestHash().equals(requestHash)) {
                if ("CALCULATING".equals(raced.status())
                        && !raced.deadlineAt().isAfter(LocalDateTime.now())) {
                    recoverTimedOutBatch(raced.batchId());
                }
                return boundedRead(deadline, () -> repository.detail(raced.batchId()));
            }
            if (recoverTimedOutScope(buildingId, periodType, window, nature)) {
                try {
                    create(deadline, batch);
                } catch (DuplicateKeyException retry) {
                    CalculationBatch retried = boundedRead(deadline,
                            () -> repository.findByIdempotency(buildingId, idempotencyKey));
                    if (retried != null && retried.requestHash().equals(requestHash)) {
                        return boundedRead(deadline, () -> repository.detail(retried.batchId()));
                    }
                    throw error(409, CONCURRENT_CALCULATION,
                            "同一建筑、周期和核算口径已有计算正在执行");
                }
            } else {
                throw error(409, CONCURRENT_CALCULATION,
                        "同一建筑、周期和核算口径已有计算正在执行");
            }
        } catch (RuntimeException exception) {
            if (timeout(deadline, exception)) {
                recoverTimeoutBatch(batchId, exception);
                throw CarbonCalculationDeadline.timeout();
            }
            throw exception;
        }
        long startNanos = System.nanoTime();
        try {
            auditWithinDeadline(deadline, actorId, buildingId, "START_CARBON_CALCULATION",
                    batchId, null, calculationSummary(batch), false);
            CalculationResult result = bounded(deadline, () -> {
                try (CarbonCalculationDeadline.Scope ignored = deadline.bind()) {
                    return calculate(batch, window, deadline);
                }
            });
            long duration = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            LocalDateTime completed = LocalDateTime.now();
            deadline.requireRemaining();
            boolean slow = duration >= properties.getSlowCalculationThreshold().toMillis();
            bounded(deadline, () -> {
                try (CarbonCalculationDeadline.Scope ignored = deadline.bind()) {
                    persistence.complete(batchId, result,
                            result.items().size() + result.failures().size(), slow, duration, completed);
                    return null;
                }
            });
            CalculationDetail detail = boundedRead(deadline, () -> repository.detail(batchId));
            auditWithinDeadline(deadline, actorId, buildingId, result.complete()
                            ? "COMPLETE_CARBON_CALCULATION" : "INCOMPLETE_CARBON_CALCULATION",
                    batchId, calculationSummary(batch), calculationSummary(detail.batch()), false);
            return detail;
        } catch (RuntimeException exception) {
            long duration = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            String code = safeCode(exception);
            String message = safeMessage(exception);
            if (timeout(deadline, exception)) {
                recoverTimeoutBatch(batchId, exception);
                code = CALCULATION_TIMEOUT;
                message = "碳计算超过约定超时";
            } else {
                String failureCode = code;
                String failureMessage = message;
                bounded(deadline, () -> {
                    try (CarbonCalculationDeadline.Scope ignored = deadline.bind()) {
                        persistence.fail(batchId, failureCode, failureMessage,
                                LocalDateTime.now(), duration);
                        return null;
                    }
                });
            }
            auditRecovery(actorId, buildingId, "FAIL_CARBON_CALCULATION", batchId,
                    calculationSummary(batch), "status=FAILED;error=" + code, false);
            if (CALCULATION_TIMEOUT.equals(code)) throw CarbonCalculationDeadline.timeout();
            throw exception;
        }
    }

    private CalculationResult calculate(CalculationBatch batch, PeriodWindow window,
                                        CarbonCalculationDeadline deadline) {
        List<ActivitySegment> activities = activityInputPort.read(batch.buildingId(),
                batch.periodType(), window.start(), window.end(), properties.getMaximumSnapshots());
        deadline.requireRemaining();
        if (activities.isEmpty()) {
            throw error(409, DEPENDENCY_UNAVAILABLE, "核算周期没有已封账的权威活动数据");
        }
        if (activities.size() > properties.getMaximumDetails()) {
            throw error(409, LIMIT_EXCEEDED, "计算明细超过单次硬上限");
        }
        String province = province(read(deadline,
                () -> ruleRepository.findBuildingRegion(batch.buildingId())));
        List<CalculatedItem> items = new ArrayList<>();
        List<CalculationFailure> failures = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        for (ActivitySegment activity : activities) {
            deadline.requireRemaining();
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
                        read(deadline, () -> ruleRepository.findCandidateFactors(
                                activity.energyItemCode(), start, end)));
                GwpVersion gwp = gwp(match.factor(), batch.resultNature(), start, end, deadline);
                items.add(core.calculate(activity, match, gwp));
            } catch (BusinessException exception) {
                if (batch.resultNature() == ResultNature.FORMAL) throw exception;
                failures.add(new CalculationFailure(activity, safeCode(exception),
                        safeMessage(exception)));
                incomplete.add(activity.snapshotId() + ':' + safeCode(exception));
            }
        }
        DenominatorSelection area = DenominatorSelection.available(null);
        DenominatorSelection population = DenominatorSelection.available(null);
        if (batch.periodType() == PeriodType.YEAR) {
            LocalDate yearStart = LocalDateTime.ofInstant(window.start(),
                    ZoneId.of(window.timezoneId())).toLocalDate();
            LocalDate yearEnd = LocalDateTime.ofInstant(window.end(),
                    ZoneId.of(window.timezoneId())).toLocalDate();
            UsageNature nature = batch.resultNature() == ResultNature.FORMAL
                    ? UsageNature.FORMAL : UsageNature.DEVELOPMENT_REFERENCE;
            area = denominator(batch.buildingId(), DenominatorType.BUILDING_AREA,
                    nature, yearStart, yearEnd, deadline);
            population = denominator(batch.buildingId(), DenominatorType.RESIDENT_POPULATION,
                    nature, yearStart, yearEnd, deadline);
        }
        List<SummaryMetric> summaries = core.summarizeWithDenominatorSelections(
                items, batch.periodType(), area, population);
        return new CalculationResult(items, failures, summaries,
                incomplete.isEmpty(), incomplete);
    }

    private DenominatorSelection denominator(String buildingId, DenominatorType type,
                                             UsageNature nature, LocalDate start, LocalDate end,
                                             CarbonCalculationDeadline deadline) {
        List<DenominatorVersion> values = read(deadline, () -> ruleRepository.findActiveDenominators(
                buildingId, type, nature, start, end));
        if (values.size() > 1) {
            String label = type == DenominatorType.BUILDING_AREA ? "建筑面积" : "常驻人数";
            return DenominatorSelection.unavailable("核算年度匹配到多个已激活" + label + "版本");
        }
        return DenominatorSelection.available(values.isEmpty() ? null : values.getFirst());
    }

    private GwpVersion gwp(FactorVersion factor, ResultNature resultNature,
                           LocalDateTime start, LocalDateTime end,
                           CarbonCalculationDeadline deadline) {
        if (!"GAS_MASS".equals(factor.resultBasis())) return null;
        UsageNature nature = resultNature == ResultNature.FORMAL
                ? UsageNature.FORMAL : UsageNature.DEVELOPMENT_REFERENCE;
        List<GwpVersion> values = read(deadline, () -> ruleRepository.findActiveGwp(
                factor.gasCode(), nature, start, end));
        if (values.isEmpty()) throw error(409, FACTOR_MISSING, "缺少完整覆盖活动周期的GWP版本");
        if (values.size() != 1) throw error(409, FACTOR_CONFLICT, "活动周期匹配到多个GWP版本");
        return values.getFirst();
    }

    private void create(CarbonCalculationDeadline deadline, CalculationBatch batch) {
        bounded(deadline, () -> {
            try (CarbonCalculationDeadline.Scope ignored = deadline.bind()) {
                persistence.create(batch);
                return null;
            }
        });
    }

    /** 截止后的状态写使用独立小预算；恢复失败不掩盖原超时，后续请求会再次原子检查。 */
    private boolean recoverTimedOutBatch(String batchId) {
        CarbonCalculationDeadline recovery = CarbonCalculationDeadline.start(
                properties.getCalculationRecoveryTimeout());
        try {
            return bounded(recovery, () -> {
                try (CarbonCalculationDeadline.Scope ignored = recovery.bind()) {
                    return persistence.timeout(batchId, LocalDateTime.now());
                }
            });
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 查询已异常返回时，执行线程已退出；无需等到请求总截止才能释放运行锁。 */
    private boolean recoverAbortedTimedOutBatch(String batchId) {
        CarbonCalculationDeadline recovery = CarbonCalculationDeadline.start(
                properties.getCalculationRecoveryTimeout());
        try {
            return bounded(recovery, () -> {
                try (CarbonCalculationDeadline.Scope ignored = recovery.bind()) {
                    return persistence.timeoutAborted(batchId, LocalDateTime.now());
                }
            });
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void recoverTimeoutBatch(String batchId, RuntimeException exception) {
        if (CarbonCalculationDeadline.causedByDatabaseTimeout(exception)) {
            recoverAbortedTimedOutBatch(batchId);
        } else {
            recoverTimedOutBatch(batchId);
        }
    }

    private boolean recoverTimedOutScope(String buildingId, PeriodType periodType,
                                         PeriodWindow window, ResultNature nature) {
        CarbonCalculationDeadline recovery = CarbonCalculationDeadline.start(
                properties.getCalculationRecoveryTimeout());
        try {
            return bounded(recovery, () -> {
                try (CarbonCalculationDeadline.Scope ignored = recovery.bind()) {
                    return persistence.timeoutExpiredScope(buildingId, periodType,
                            window.start(), window.end(), nature, LocalDateTime.now()) > 0;
                }
            });
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 超时后的审计不能再次占用原 HTTP 预算；写失败不能覆盖已经确定的超时结果。 */
    private void auditRecovery(long actorId, String buildingId, String action, String batchId,
                               String before, String after, boolean selfApproval) {
        CarbonCalculationDeadline recovery = CarbonCalculationDeadline.start(
                properties.getCalculationRecoveryTimeout());
        try {
            bounded(recovery, () -> {
                try (CarbonCalculationDeadline.Scope ignored = recovery.bind()) {
                    persistence.audit(() -> audit(actorId, buildingId, action, batchId, before, after, selfApproval));
                }
                return null;
            });
        } catch (RuntimeException ignored) {
            // 不能让审计存储的暂时不可用覆盖已经确定的核算超时响应。
        }
    }

    private void auditWithinDeadline(CarbonCalculationDeadline deadline, long actorId,
                                     String buildingId, String action, String batchId,
                                     String before, String after, boolean selfApproval) {
        bounded(deadline, () -> {
            try (CarbonCalculationDeadline.Scope ignored = deadline.bind()) {
                persistence.audit(() -> audit(actorId, buildingId, action, batchId, before, after, selfApproval));
            }
            return null;
        });
    }

    private <T> T boundedRead(CarbonCalculationDeadline deadline, Supplier<T> operation) {
        return bounded(deadline, () -> read(deadline, operation));
    }

    private <T> T read(CarbonCalculationDeadline deadline, Supplier<T> operation) {
        deadline.requireRemaining();
        try (CarbonCalculationDeadline.Scope ignored = deadline.bind()) {
            return persistence.read(operation);
        }
    }

    /**
     * 每个尚未实际退出的计算步骤都占用一个许可。若 JDBC 或上游忽略中断，许可不会提前归还，
     * 后续请求只能在自己的截止时间内等待，避免超时请求在后台无界积压。
     */
    private <T> T bounded(CarbonCalculationDeadline deadline, Callable<T> operation) {
        deadline.requireRemaining();
        try {
            if (!inFlightCalculations.tryAcquire(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS)) {
                throw CarbonCalculationDeadline.timeout();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw error(503, DEPENDENCY_UNAVAILABLE, "碳计算执行被中断");
        }
        CompletableFuture<T> outcome = new CompletableFuture<>();
        Thread worker;
        String traceId = MDC.get(TraceContext.MDC_KEY);
        try {
            worker = Thread.ofVirtual().name("carbon-calculation-deadline-").start(() -> {
                String previousTraceId = MDC.get(TraceContext.MDC_KEY);
                if (traceId == null) {
                    MDC.remove(TraceContext.MDC_KEY);
                } else {
                    MDC.put(TraceContext.MDC_KEY, traceId);
                }
                try {
                    outcome.complete(operation.call());
                } catch (Throwable failure) {
                    outcome.completeExceptionally(failure);
                } finally {
                    if (previousTraceId == null) {
                        MDC.remove(TraceContext.MDC_KEY);
                    } else {
                        MDC.put(TraceContext.MDC_KEY, previousTraceId);
                    }
                    inFlightCalculations.release();
                }
            });
        } catch (RuntimeException failure) {
            inFlightCalculations.release();
            throw failure;
        }
        try {
            return outcome.get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            worker.interrupt();
            throw CarbonCalculationDeadline.timeout();
        } catch (InterruptedException exception) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw error(503, DEPENDENCY_UNAVAILABLE, "碳计算执行被中断");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("碳计算执行失败", cause);
        }
    }

    private static boolean timeout(CarbonCalculationDeadline deadline, Throwable failure) {
        return CALCULATION_TIMEOUT.equals(safeCode(failure)) || deadline.expired()
                || CarbonCalculationDeadline.causedByDatabaseTimeout(failure);
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
