package com.platform.iot.reliability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.iot.reliability.api.TelemetryReceiptContracts.FailureView;
import com.platform.iot.reliability.api.TelemetryReceiptContracts.FailureStatistics;
import com.platform.iot.reliability.api.TelemetryReceiptContracts.ReceiptDetail;
import com.platform.iot.reliability.api.TelemetryReceiptContracts.ReceiptStatistics;
import com.platform.iot.reliability.api.TelemetryReceiptContracts.ReceiptView;
import com.platform.iot.reliability.api.TelemetryReceiptContracts.TransportFailureView;
import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import com.platform.iot.reliability.model.TelemetryReceipt;
import com.platform.iot.reliability.model.TelemetryReceiptFailure;
import com.platform.iot.reliability.model.MqttFailureAggregate;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Service
/** 按当前用户建筑范围提供 V2 回执、处理链详情和汇总统计。 */
public class TelemetryReceiptQueryService {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration HOT_RECEIPT_WINDOW = Duration.ofHours(24);
    private static final Duration FAILURE_RETENTION_WINDOW = Duration.ofDays(180);
    private static final String WINDOW_SCOPE = "HOT_RECEIPT_WINDOW";
    private static final String FAILURE_WINDOW_SCOPE = "FAILURE_RETENTION_WINDOW";
    private final TelemetryReceiptMapper receiptMapper;
    private final TelemetryReceiptFailureMapper failureMapper;
    private final BuildingScopeService buildingScopeService;
    private final MqttFailureAggregateMapper mqttFailureMapper;
    private final Clock clock;

    @Autowired
    public TelemetryReceiptQueryService(
            TelemetryReceiptMapper receiptMapper,
            TelemetryReceiptFailureMapper failureMapper,
            BuildingScopeService buildingScopeService,
            MqttFailureAggregateMapper mqttFailureMapper) {
        this(receiptMapper, failureMapper, buildingScopeService, mqttFailureMapper,
                Clock.system(PROJECT_ZONE));
    }

    TelemetryReceiptQueryService(
            TelemetryReceiptMapper receiptMapper,
            TelemetryReceiptFailureMapper failureMapper,
            BuildingScopeService buildingScopeService,
            MqttFailureAggregateMapper mqttFailureMapper,
            Clock clock) {
        this.receiptMapper = receiptMapper;
        this.failureMapper = failureMapper;
        this.buildingScopeService = buildingScopeService;
        this.mqttFailureMapper = mqttFailureMapper;
        this.clock = clock;
    }

    public PageResponse<ReceiptView> list(
            Long userId,
            Set<String> roles,
            String buildingId,
            String equipmentId,
            String receiptStatus,
            Long fromEpochMillis,
            Long toEpochMillis,
            int page,
            int size) {
        int safePage = requireRange(page, 1, 100_000, "page");
        int safeSize = requireRange(size, 1, 100, "size");
        QueryWindow window = requireHotWindow(fromEpochMillis, toEpochMillis);
        LambdaQueryWrapper<TelemetryReceipt> query = new LambdaQueryWrapper<>();
        applyScope(query, userId, roles, buildingId);
        query.eq(hasText(equipmentId), TelemetryReceipt::getEquipId, equipmentId)
                .eq(hasText(receiptStatus), TelemetryReceipt::getReceiptStatus, receiptStatus)
                .ge(TelemetryReceipt::getPersistedAt, window.from())
                .lt(TelemetryReceipt::getPersistedAt, window.to())
                .orderByDesc(TelemetryReceipt::getPersistedAt)
                .orderByDesc(TelemetryReceipt::getCanonicalMessageId);
        Page<TelemetryReceipt> result = receiptMapper.selectPage(
                new Page<>(safePage, safeSize), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::view).toList());
    }

    public ReceiptDetail detail(
            Long userId, Set<String> roles, String canonicalMessageId) {
        TelemetryReceipt receipt = receiptMapper.selectById(canonicalMessageId);
        if (receipt == null || !buildingScopeService.canAccess(
                userId, roles, receipt.getBuildingId())) {
            throw new BusinessException(404, "TELEMETRY_RECEIPT_NOT_FOUND", "回执不存在或不可见");
        }
        List<FailureView> failures = failureMapper.selectList(
                        new LambdaQueryWrapper<TelemetryReceiptFailure>()
                                .eq(TelemetryReceiptFailure::getCanonicalMessageId,
                                        canonicalMessageId)
                                .orderByAsc(TelemetryReceiptFailure::getOccurredAt))
                .stream().map(this::failureView).toList();
        return new ReceiptDetail(view(receipt), failures);
    }

    public ReceiptStatistics statistics(
            Long userId,
            Set<String> roles,
            String buildingId,
            Long fromEpochMillis,
            Long toEpochMillis) {
        QueryWindow window = requireHotWindow(fromEpochMillis, toEpochMillis);
        LambdaQueryWrapper<TelemetryReceipt> base = new LambdaQueryWrapper<>();
        applyScope(base, userId, roles, buildingId);
        applyWindow(base, window);
        long persisted = receiptMapper.selectCount(base);
        long direct = receiptMapper.selectCount(copyScope(userId, roles, buildingId, window)
                .eq(TelemetryReceipt::getActualAckMode, AckMode.DEVICE_DIRECT.name()));
        long proxy = receiptMapper.selectCount(copyScope(userId, roles, buildingId, window)
                .eq(TelemetryReceipt::getActualAckMode, AckMode.ADAPTER_PROXY.name()));
        long evidence = receiptMapper.selectCount(copyScope(userId, roles, buildingId, window)
                .eq(TelemetryReceipt::getActualAckMode, AckMode.EVIDENCE_ONLY.name()));

        QueryWrapper<TelemetryReceipt> duplicateQuery = new QueryWrapper<>();
        applyScope(duplicateQuery, userId, roles, buildingId);
        applyWindow(duplicateQuery, window);
        duplicateQuery.select("COALESCE(SUM(attempt_count - 1), 0)");
        Object duplicateValue = receiptMapper.selectObjs(duplicateQuery).stream()
                .findFirst().orElse(0);

        long conflicts = failureCount(
                userId, roles, buildingId, "MESSAGE_CONFLICT", window);
        long rejected = failureCount(
                userId, roles, buildingId, "PLATFORM_REJECTED", window);
        long ackFailures = failureCount(
                userId, roles, buildingId, "APPLICATION_ACK_PUBLISH_FAILED", window);
        return new ReceiptStatistics(persisted, ((Number) duplicateValue).longValue(),
                direct, proxy, evidence, conflicts, rejected, ackFailures,
                window.fromEpochMillis(), window.toEpochMillis(), WINDOW_SCOPE);
    }

    public FailureStatistics failureStatistics(
            Long userId,
            Set<String> roles,
            String buildingId,
            Long fromEpochMillis,
            Long toEpochMillis) {
        QueryWindow window = requireFailureWindow(fromEpochMillis, toEpochMillis);
        long total = failureCount(userId, roles, buildingId, null, window);
        long conflicts = failureCount(
                userId, roles, buildingId, "MESSAGE_CONFLICT", window);
        long rejected = failureCount(
                userId, roles, buildingId, "PLATFORM_REJECTED", window);
        long storage = failureCount(userId, roles, buildingId,
                "STORAGE_TEMPORARILY_UNAVAILABLE", window);
        long ackFailures = failureCount(userId, roles, buildingId,
                "APPLICATION_ACK_PUBLISH_FAILED", window);
        return new FailureStatistics(total, conflicts, rejected, storage, ackFailures,
                window.fromEpochMillis(), window.toEpochMillis(), FAILURE_WINDOW_SCOPE);
    }

    public PageResponse<TransportFailureView> transportFailures(int page, int size) {
        int safePage = requireRange(page, 1, 100_000, "page");
        int safeSize = requireRange(size, 1, 100, "size");
        Page<MqttFailureAggregate> result = mqttFailureMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<MqttFailureAggregate>()
                        .orderByDesc(MqttFailureAggregate::getBucketStart)
                        .orderByDesc(MqttFailureAggregate::getAggregateId));
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(item -> new TransportFailureView(
                        requiredEpoch(item.getBucketStart()), item.getComponent(),
                        item.getFailureCategory(), item.getBrokerEndpoint(),
                        item.getOccurrenceCount(), requiredEpoch(item.getFirstOccurredAt()),
                        requiredEpoch(item.getLastOccurredAt()))).toList());
    }

    private long failureCount(
            Long userId,
            Set<String> roles,
            String buildingId,
            String code,
            QueryWindow window) {
        Set<String> accessible = allowedBuildings(userId, roles, buildingId);
        LambdaQueryWrapper<TelemetryReceiptFailure> query =
                new LambdaQueryWrapper<TelemetryReceiptFailure>()
                        .eq(code != null, TelemetryReceiptFailure::getFailureCode, code)
                        .ge(TelemetryReceiptFailure::getOccurredAt, window.from())
                        .lt(TelemetryReceiptFailure::getOccurredAt, window.to());
        if (accessible != null) {
            if (accessible.isEmpty()) {
                return 0;
            }
            query.in(TelemetryReceiptFailure::getBuildingId, accessible);
        }
        return failureMapper.selectCount(query);
    }

    private LambdaQueryWrapper<TelemetryReceipt> copyScope(
            Long userId,
            Set<String> roles,
            String buildingId,
            QueryWindow window) {
        LambdaQueryWrapper<TelemetryReceipt> query = new LambdaQueryWrapper<>();
        applyScope(query, userId, roles, buildingId);
        applyWindow(query, window);
        return query;
    }

    private void applyScope(
            LambdaQueryWrapper<TelemetryReceipt> query,
            Long userId,
            Set<String> roles,
            String buildingId) {
        Set<String> accessible = allowedBuildings(userId, roles, buildingId);
        if (accessible != null) {
            if (accessible.isEmpty()) {
                query.eq(TelemetryReceipt::getBuildingId, "__NO_ACCESS__");
            } else {
                query.in(TelemetryReceipt::getBuildingId, accessible);
            }
        }
    }

    private void applyWindow(
            LambdaQueryWrapper<TelemetryReceipt> query, QueryWindow window) {
        query.ge(TelemetryReceipt::getPersistedAt, window.from())
                .lt(TelemetryReceipt::getPersistedAt, window.to());
    }

    private void applyWindow(
            QueryWrapper<TelemetryReceipt> query, QueryWindow window) {
        query.ge("persisted_at", window.from()).lt("persisted_at", window.to());
    }

    private void applyScope(
            QueryWrapper<TelemetryReceipt> query,
            Long userId,
            Set<String> roles,
            String buildingId) {
        Set<String> accessible = allowedBuildings(userId, roles, buildingId);
        if (accessible != null) {
            if (accessible.isEmpty()) {
                query.eq("building_id", "__NO_ACCESS__");
            } else {
                query.in("building_id", accessible);
            }
        }
    }

    private Set<String> allowedBuildings(
            Long userId, Set<String> roles, String requestedBuildingId) {
        if (hasText(requestedBuildingId)) {
            buildingScopeService.checkAccess(userId, roles, requestedBuildingId);
            return Set.of(requestedBuildingId);
        }
        return buildingScopeService.getAccessibleBuildingIds(userId, roles);
    }

    private ReceiptView view(TelemetryReceipt receipt) {
        return new ReceiptView(
                receipt.getCanonicalMessageId(), receipt.getBuildingId(), receipt.getEquipId(),
                receipt.getProfileCode(), receipt.getSourceMessageId(), receipt.getSourceSeq(),
                epoch(receipt.getCollectedAt()), requiredEpoch(receipt.getAdapterReceivedAt()),
                requiredEpoch(receipt.getFirstPlatformReceivedAt()),
                requiredEpoch(receipt.getLastPlatformReceivedAt()),
                requiredEpoch(receipt.getPersistedAt()), epoch(receipt.getRetransmittedAt()),
                receipt.getBatchId(), receipt.getIdSource(), receipt.getTimeSource(),
                receipt.getDedupMode(), receipt.getConfiguredAckMode(), receipt.getActualAckMode(),
                receipt.getDowngradeReason(), receipt.getReceiptStatus(), receipt.getResultCode(),
                receipt.getMetricCount(), receipt.getAttemptCount(), "UNKNOWN", "UNKNOWN",
                "NOT_TRACKED", "NOT_TRACKED", null);
    }

    private FailureView failureView(TelemetryReceiptFailure failure) {
        return new FailureView(failure.getFailureStage(), failure.getFailureCode(),
                failure.getSafeDetail(), requiredEpoch(failure.getOccurredAt()));
    }

    private Long epoch(LocalDateTime value) {
        return value == null ? null : requiredEpoch(value);
    }

    private long requiredEpoch(LocalDateTime value) {
        return value.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private int requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new BusinessException(400, "INVALID_PAGE_ARGUMENT",
                    field + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private QueryWindow requireHotWindow(Long requestedFrom, Long requestedTo) {
        long toEpochMillis = requestedTo == null ? clock.millis() : requestedTo;
        long fromEpochMillis;
        try {
            fromEpochMillis = requestedFrom == null
                    ? Math.subtractExact(toEpochMillis, HOT_RECEIPT_WINDOW.toMillis())
                    : requestedFrom;
        } catch (ArithmeticException exception) {
            throw invalidWindow();
        }
        if (fromEpochMillis < 0 || toEpochMillis <= fromEpochMillis) {
            throw invalidWindow();
        }
        long span;
        try {
            span = Math.subtractExact(toEpochMillis, fromEpochMillis);
        } catch (ArithmeticException exception) {
            throw invalidWindow();
        }
        if (span > HOT_RECEIPT_WINDOW.toMillis()) {
            throw new BusinessException(400, "TELEMETRY_RECEIPT_WINDOW_TOO_LARGE",
                    "成功回执单次查询跨度不能超过24小时");
        }
        try {
            return new QueryWindow(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(fromEpochMillis), PROJECT_ZONE),
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(toEpochMillis), PROJECT_ZONE),
                    fromEpochMillis, toEpochMillis);
        } catch (RuntimeException exception) {
            throw invalidWindow();
        }
    }

    private BusinessException invalidWindow() {
        return new BusinessException(400, "INVALID_TELEMETRY_RECEIPT_WINDOW",
                "成功回执查询时间范围无效");
    }

    private QueryWindow requireFailureWindow(Long requestedFrom, Long requestedTo) {
        long toEpochMillis = requestedTo == null ? clock.millis() : requestedTo;
        long fromEpochMillis;
        try {
            fromEpochMillis = requestedFrom == null
                    ? Math.subtractExact(toEpochMillis, FAILURE_RETENTION_WINDOW.toMillis())
                    : requestedFrom;
        } catch (ArithmeticException exception) {
            throw invalidFailureWindow();
        }
        if (fromEpochMillis < 0 || toEpochMillis <= fromEpochMillis) {
            throw invalidFailureWindow();
        }
        long span;
        try {
            span = Math.subtractExact(toEpochMillis, fromEpochMillis);
        } catch (ArithmeticException exception) {
            throw invalidFailureWindow();
        }
        if (span > FAILURE_RETENTION_WINDOW.toMillis()) {
            throw new BusinessException(400, "TELEMETRY_FAILURE_WINDOW_TOO_LARGE",
                    "异常统计单次查询跨度不能超过180天");
        }
        try {
            return new QueryWindow(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(fromEpochMillis), PROJECT_ZONE),
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(toEpochMillis), PROJECT_ZONE),
                    fromEpochMillis, toEpochMillis);
        } catch (RuntimeException exception) {
            throw invalidFailureWindow();
        }
    }

    private BusinessException invalidFailureWindow() {
        return new BusinessException(400, "INVALID_TELEMETRY_FAILURE_WINDOW",
                "异常统计查询时间范围无效");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record QueryWindow(
            LocalDateTime from,
            LocalDateTime to,
            long fromEpochMillis,
            long toEpochMillis) {
    }
}
