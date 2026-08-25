package com.platform.iot.reliability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.iot.reliability.api.TelemetryReceiptContracts.FailureView;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Service
/** 按当前用户建筑范围提供 V2 回执、处理链详情和汇总统计。 */
public class TelemetryReceiptQueryService {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private final TelemetryReceiptMapper receiptMapper;
    private final TelemetryReceiptFailureMapper failureMapper;
    private final BuildingScopeService buildingScopeService;
    private final MqttFailureAggregateMapper mqttFailureMapper;

    public TelemetryReceiptQueryService(
            TelemetryReceiptMapper receiptMapper,
            TelemetryReceiptFailureMapper failureMapper,
            BuildingScopeService buildingScopeService,
            MqttFailureAggregateMapper mqttFailureMapper) {
        this.receiptMapper = receiptMapper;
        this.failureMapper = failureMapper;
        this.buildingScopeService = buildingScopeService;
        this.mqttFailureMapper = mqttFailureMapper;
    }

    public PageResponse<ReceiptView> list(
            Long userId,
            Set<String> roles,
            String buildingId,
            String equipmentId,
            String receiptStatus,
            int page,
            int size) {
        int safePage = requireRange(page, 1, 100_000, "page");
        int safeSize = requireRange(size, 1, 100, "size");
        LambdaQueryWrapper<TelemetryReceipt> query = new LambdaQueryWrapper<>();
        applyScope(query, userId, roles, buildingId);
        query.eq(hasText(equipmentId), TelemetryReceipt::getEquipId, equipmentId)
                .eq(hasText(receiptStatus), TelemetryReceipt::getReceiptStatus, receiptStatus)
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
            Long userId, Set<String> roles, String buildingId) {
        LambdaQueryWrapper<TelemetryReceipt> base = new LambdaQueryWrapper<>();
        applyScope(base, userId, roles, buildingId);
        long persisted = receiptMapper.selectCount(base);
        long direct = receiptMapper.selectCount(copyScope(userId, roles, buildingId)
                .eq(TelemetryReceipt::getActualAckMode, AckMode.DEVICE_DIRECT.name()));
        long proxy = receiptMapper.selectCount(copyScope(userId, roles, buildingId)
                .eq(TelemetryReceipt::getActualAckMode, AckMode.ADAPTER_PROXY.name()));
        long evidence = receiptMapper.selectCount(copyScope(userId, roles, buildingId)
                .eq(TelemetryReceipt::getActualAckMode, AckMode.EVIDENCE_ONLY.name()));

        QueryWrapper<TelemetryReceipt> duplicateQuery = new QueryWrapper<>();
        applyScope(duplicateQuery, userId, roles, buildingId);
        duplicateQuery.select("COALESCE(SUM(attempt_count - 1), 0)");
        Object duplicateValue = receiptMapper.selectObjs(duplicateQuery).stream()
                .findFirst().orElse(0);

        long conflicts = failureCount(userId, roles, buildingId, "MESSAGE_CONFLICT");
        long rejected = failureCount(userId, roles, buildingId, "PLATFORM_REJECTED");
        long ackFailures = failureCount(
                userId, roles, buildingId, "APPLICATION_ACK_PUBLISH_FAILED");
        return new ReceiptStatistics(persisted, ((Number) duplicateValue).longValue(),
                direct, proxy, evidence, conflicts, rejected, ackFailures);
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
            Long userId, Set<String> roles, String buildingId, String code) {
        Set<String> accessible = allowedBuildings(userId, roles, buildingId);
        LambdaQueryWrapper<TelemetryReceiptFailure> query =
                new LambdaQueryWrapper<TelemetryReceiptFailure>()
                        .eq(TelemetryReceiptFailure::getFailureCode, code);
        if (accessible != null) {
            if (accessible.isEmpty()) {
                return 0;
            }
            query.in(TelemetryReceiptFailure::getBuildingId, accessible);
        }
        return failureMapper.selectCount(query);
    }

    private LambdaQueryWrapper<TelemetryReceipt> copyScope(
            Long userId, Set<String> roles, String buildingId) {
        LambdaQueryWrapper<TelemetryReceipt> query = new LambdaQueryWrapper<>();
        applyScope(query, userId, roles, buildingId);
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
                receipt.getMetricCount(), receipt.getAttemptCount(), receipt.getDevicePubackState(),
                receipt.getAdapterPublishPubackState(), receipt.getPlatformConsumerAckState(),
                receipt.getApplicationAckPubackState(), epoch(receipt.getApplicationAckPublishedAt()));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
