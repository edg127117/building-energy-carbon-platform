package com.platform.iot.dataquality;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BuildingService;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.dto.DataQualityRecalculationDtos;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.security.FormalRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 人工重算批次的幂等受理、重叠互斥和管理查询服务。
 *
 * <p>提交事务先锁建筑父记录，再锁同建筑活动批次，防止多实例同时受理交叠范围。
 * 本服务只写 MySQL 任务，不同步访问 TDengine；后台执行器按持久化游标异步处理。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "recalculation-enabled"},
        havingValue = "true")
public class DataQualityRecalculationJobService {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final int MAX_POINTS = 100;
    private static final String PUBLIC_JOB_FAILURE_SUMMARY =
            "人工重算任务执行失败，请查看服务端日志";
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final RecalculationJobRepository jobRepository;
    private final FillTaskRepository fillTaskRepository;
    private final BuildingService buildingService;
    private final BizDataPointService pointService;
    private final DataQualityFillTaskService fillTaskService;
    private final ObjectMapper objectMapper;

    /**
     * 受理异常补全任务的作废并重算请求；HTTP 线程只创建或恢复 MySQL 批次。
     */
    @Transactional(rollbackFor = Exception.class)
    public DataQualityRecalculationDtos.Response submitVoid(
            long operatorId,
            Collection<String> roles,
            String taskId,
            String reason,
            long now) {
        requireAdmin(operatorId, roles);
        String normalizedTaskId = requireText(taskId, "补全任务ID不能为空");
        String normalizedReason = requireReason(reason);
        String key = RecalculationJobIdempotency.voidJob(normalizedTaskId);
        BizDataQualityRecalcJob exact =
                jobRepository.findByIdempotencyKey(key).orElse(null);
        // 幂等复用必须早于补全任务和测点现状校验：既有任务可能已经成功，
        // 但其历史测点随后被停用或删除，此时重复请求仍应返回原任务。
        if (exact != null && exact.getStatus() != RecalculationJobStatus.FAILED) {
            return toResponse(exact);
        }
        BizDataQualityFillTask task = fillTaskRepository
                .findAuditById(normalizedTaskId)
                .orElseThrow(() -> new BusinessException(404, "补全任务不存在"));
        if (task.getApplyStatus() == FillApplyStatus.VOIDED
                && exact == null) {
            throw new BusinessException(409, "补全任务已经作废");
        }
        String buildingId = requireText(task.getBuildingId(), "补全任务建筑缺失");
        String taskPointId = requireText(
                task.getPointId(), "补全任务测点缺失");
        List<String> pointIds = normalizePointIds(List.of(taskPointId));
        long from = toEpochRequired(task.getStartMinute(), "补全任务开始时间缺失");
        long to = toEpochRequired(task.getEndMinute(), "补全任务结束时间缺失");
        validateCompletedPeriod(from, to, now);
        validatePoints(buildingId, pointIds);
        return submit(
                key, RecalculationJobType.VOID_AND_RECALCULATE,
                buildingId, pointIds, from, to, normalizedTaskId,
                normalizedReason, operatorId, now);
    }

    /**
     * 受理指定建筑和测点范围的人工重算；实际 TDengine 处理由后台执行器完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public DataQualityRecalculationDtos.Response submitRange(
            long operatorId,
            Collection<String> roles,
            DataQualityRecalculationDtos.RecalculateRequest request,
            long now) {
        requireAdmin(operatorId, roles);
        if (request == null) {
            throw new BusinessException(400, "重算请求不能为空");
        }
        String buildingId = requireText(request.buildingId(), "建筑ID不能为空");
        validateBuildingId(buildingId);
        List<String> pointIds = normalizePointIds(request.pointIds());
        String reason = requireReason(request.reason());
        long from = requireTimestamp(request.fromInclusive(), "开始时间不能为空");
        long to = requireTimestamp(request.toExclusive(), "结束时间不能为空");
        validateCompletedPeriod(from, to, now);
        String key;
        try {
            key = RecalculationJobIdempotency.rangeJob(
                    operatorId, buildingId, pointIds, from, to, reason);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, exception.getMessage());
        }
        BizDataQualityRecalcJob exact =
                jobRepository.findByIdempotencyKey(key).orElse(null);
        if (exact != null && exact.getStatus() != RecalculationJobStatus.FAILED) {
            return toResponse(exact);
        }
        validatePoints(buildingId, pointIds);
        return submit(
                key, RecalculationJobType.RANGE_RECALCULATE,
                buildingId, pointIds, from, to, null, reason, operatorId, now);
    }

    /** 平台管理员在 MySQL 侧按状态、类型和半开时间范围稳定分页。 */
    public IPage<DataQualityRecalculationDtos.Response> page(
            Collection<String> roles,
            int pageNum,
            int pageSize,
            String buildingId,
            RecalculationJobType jobType,
            RecalculationJobStatus status,
            Long fromInclusive,
            Long toExclusive) {
        requireAdminRole(roles);
        String normalizedBuilding = trimToNull(buildingId);
        validateBuildingId(normalizedBuilding);
        if (fromInclusive != null && toExclusive != null
                && fromInclusive >= toExclusive) {
            throw new BusinessException(400, "查询结束时间必须晚于开始时间");
        }
        try {
            return jobRepository.findPage(
                            pageNum, pageSize, normalizedBuilding, jobType, status,
                            toLocal(fromInclusive), toLocal(toExclusive))
                    .convert(this::toResponse);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, exception.getMessage());
        }
    }

    /** 返回批次进度及其通过 recalcJobId 关联的 Q1/Q2 子任务。 */
    public DataQualityRecalculationDtos.Detail detail(
            Collection<String> roles, String jobId) {
        requireAdminRole(roles);
        BizDataQualityRecalcJob job = jobRepository.findById(
                        requireText(jobId, "重算任务ID不能为空"))
                .orElseThrow(() -> new BusinessException(404, "重算任务不存在"));
        var children = fillTaskRepository.findByRecalculationJobId(job.getJobId())
                .stream()
                .map(fillTaskService::toResponse)
                .toList();
        return new DataQualityRecalculationDtos.Detail(toResponse(job), children);
    }

    private DataQualityRecalculationDtos.Response submit(
            String key,
            RecalculationJobType type,
            String buildingId,
            List<String> pointIds,
            long from,
            long to,
            String supersedesTaskId,
            String reason,
            long operatorId,
            long now) {
        BizDataQualityRecalcJob exact =
                jobRepository.findByIdempotencyKey(key).orElse(null);
        if (exact != null && exact.getStatus() != RecalculationJobStatus.FAILED) {
            return toResponse(exact);
        }

        buildingService.lockExistingForUpdate(buildingId);
        // 建筑锁前的快速幂等读不能作为并发最终判断；另一个事务可能刚在本事务
        // 等待建筑锁期间插入同一任务。MySQL 默认 REPEATABLE READ 的普通重读
        // 仍可能停留在旧快照，必须使用 FOR UPDATE 当前读取得最终状态。
        BizDataQualityRecalcJob lockedExact =
                jobRepository.findByIdempotencyKeyForUpdate(key).orElse(null);
        if (lockedExact != null
                && lockedExact.getStatus() != RecalculationJobStatus.FAILED) {
            return toResponse(lockedExact);
        }
        if (lockedExact != null) {
            exact = lockedExact;
        }
        List<BizDataQualityRecalcJob> overlaps =
                jobRepository.findOverlappingForUpdate(
                        buildingId, toLocal(from), toLocal(to));
        for (BizDataQualityRecalcJob overlap : overlaps) {
            if (exact != null && Objects.equals(
                    exact.getJobId(), overlap.getJobId())) {
                continue;
            }
            if (!java.util.Collections.disjoint(
                    pointIds, decodePointIds(overlap.getPointIdsJson()))) {
                throw new BusinessException(
                        409, "同一建筑存在测点和时间重叠的活动重算任务");
            }
        }
        if (exact != null) {
            jobRepository.resumeFailed(exact.getJobId());
            exact.setStatus(RecalculationJobStatus.WAITING);
            exact.setLastError(null);
            exact.setFinishedAt(null);
            exact.setUpdateTime(toLocal(now));
            return toResponse(exact);
        }

        LocalDateTime at = toLocal(now);
        BizDataQualityRecalcJob candidate = new BizDataQualityRecalcJob();
        candidate.setIdempotencyKey(key);
        candidate.setJobType(type);
        candidate.setBuildingId(buildingId);
        candidate.setPointIdsJson(encodePointIds(pointIds));
        candidate.setFromMinute(toLocal(from));
        candidate.setToMinute(toLocal(to));
        candidate.setSupersedesTaskId(supersedesTaskId);
        candidate.setReason(reason);
        candidate.setOperatorId(operatorId);
        candidate.setStatus(RecalculationJobStatus.WAITING);
        candidate.setPhase(type == RecalculationJobType.VOID_AND_RECALCULATE
                ? RecalculationJobPhase.VOIDING
                : RecalculationJobPhase.RECALCULATING);
        candidate.setCursorMinute(toLocal(from));
        candidate.setCreateTime(at);
        candidate.setUpdateTime(at);
        return toResponse(jobRepository.insert(candidate));
    }

    private void validatePoints(String buildingId, List<String> pointIds) {
        List<BizDataPoint> points = pointService.listByIds(pointIds);
        if (points.size() != pointIds.size()) {
            throw new BusinessException(404, "测点不存在");
        }
        if (points.stream().anyMatch(point ->
                !buildingId.equals(point.getBuildingId()))) {
            throw new BusinessException(400, "测点不属于请求建筑");
        }
    }

    private List<String> normalizePointIds(List<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            throw new BusinessException(400, "pointIds不能为空");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String pointId : pointIds) {
            String value = requireText(pointId, "测点ID不能为空");
            if (value.length() > 32) {
                throw new BusinessException(400, "测点ID不能超过32个字符");
            }
            normalized.add(value);
        }
        if (normalized.size() > MAX_POINTS) {
            throw new BusinessException(400, "pointIds不能超过100个");
        }
        return List.copyOf(normalized);
    }

    private void validateCompletedPeriod(long from, long to, long now) {
        if (Math.floorMod(from, MINUTE_MILLIS) != 0L
                || Math.floorMod(to, MINUTE_MILLIS) != 0L) {
            throw new BusinessException(400, "重算时间必须对齐到分钟");
        }
        if (from >= to) {
            throw new BusinessException(400, "结束时间必须晚于开始时间");
        }
        long completedUpperBound = now - Math.floorMod(now, MINUTE_MILLIS);
        if (to > completedUpperBound) {
            throw new BusinessException(400, "只能重算已经完成的分钟");
        }
    }

    private void requireAdmin(long operatorId, Collection<String> roles) {
        if (operatorId <= 0L) {
            throw new BusinessException(400, "操作人ID无效");
        }
        requireAdminRole(roles);
    }

    private void requireAdminRole(Collection<String> roles) {
        if (roles == null || roles.stream().noneMatch(
                role -> FormalRole.PLATFORM_ADMIN.name().equalsIgnoreCase(role))) {
            throw new BusinessException(403, "只有平台管理员可以管理人工重算任务");
        }
    }

    private void validateBuildingId(String buildingId) {
        if (buildingId != null && buildingId.length() > 32) {
            throw new BusinessException(400, "建筑ID不能超过32个字符");
        }
    }

    private DataQualityRecalculationDtos.Response toResponse(
            BizDataQualityRecalcJob job) {
        RecalculationJobType jobType =
                requireStored(job.getJobType(), "jobType缺失");
        RecalculationJobStatus status =
                requireStored(job.getStatus(), "status缺失");
        RecalculationJobPhase phase =
                requireStored(job.getPhase(), "phase缺失");
        Long storedOperatorId =
                requireStored(job.getOperatorId(), "operatorId缺失");
        if (storedOperatorId <= 0L) {
            throw new BusinessException(409, "operatorId无效");
        }
        return new DataQualityRecalculationDtos.Response(
                job.getJobId(), jobType, job.getBuildingId(),
                decodePointIds(job.getPointIdsJson()),
                toEpochRequired(job.getFromMinute(), "fromMinute缺失"),
                toEpochRequired(job.getToMinute(), "toMinute缺失"),
                job.getSupersedesTaskId(), job.getReason(), storedOperatorId,
                status, phase,
                toEpochRequired(job.getCursorMinute(), "cursorMinute缺失"),
                zero(job.getQ0Count()), zero(job.getQ1Count()), zero(job.getQ2Count()),
                zero(job.getMissingCount()), zero(job.getVoidedCount()),
                zero(job.getReplacedCount()),
                publicFailureSummary(job.getLastError()),
                toEpoch(job.getStartedAt()), toEpoch(job.getFinishedAt()),
                toEpochRequired(job.getCreateTime(), "createTime缺失"),
                toEpochRequired(job.getUpdateTime(), "updateTime缺失"));
    }

    private String encodePointIds(List<String> pointIds) {
        try {
            return objectMapper.writeValueAsString(pointIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("测点集合无法序列化", exception);
        }
    }

    private List<String> decodePointIds(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (JsonProcessingException
                 | IllegalArgumentException
                 | NullPointerException exception) {
            throw new BusinessException(409, "重算任务测点证据已损坏");
        }
    }

    /**
     * MySQL 保留执行器诊断原文，管理 API 只公开固定摘要，避免泄漏 SQL、JDBC
     * 地址、内部表名或堆栈。
     */
    private String publicFailureSummary(String lastError) {
        return lastError == null || lastError.isBlank()
                ? null
                : PUBLIC_JOB_FAILURE_SUMMARY;
    }

    private <T> T requireStored(T value, String message) {
        if (value == null) {
            throw new BusinessException(409, message);
        }
        return value;
    }

    private long requireTimestamp(Long value, String message) {
        if (value == null) {
            throw new BusinessException(400, message);
        }
        return value;
    }

    private String requireReason(String value) {
        String normalized = requireText(value, "操作原因不能为空");
        if (normalized.length() > 500) {
            throw new BusinessException(400, "操作原因不能超过500个字符");
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(400, message);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDateTime toLocal(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
        } catch (RuntimeException exception) {
            throw new BusinessException(400, "时间戳超出允许范围");
        }
    }

    private Long toEpoch(LocalDateTime value) {
        return value == null ? null
                : value.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private long toEpochRequired(LocalDateTime value, String message) {
        if (value == null) {
            throw new BusinessException(409, message);
        }
        return value.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private int zero(Integer value) {
        return value == null ? 0 : value;
    }
}
