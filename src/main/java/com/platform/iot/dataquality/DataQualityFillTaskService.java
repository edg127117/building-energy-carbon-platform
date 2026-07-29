package com.platform.iot.dataquality;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.dto.DataQualityFillDtos;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 补全任务管理接口的查询与失败重试编排层。
 *
 * <p>列表先由 {@link BuildingScopeService} 计算建筑范围，再交给 MySQL 分页；
 * 失败重试复用后台恢复服务的同 taskId 入口，不创建审批或重复任务。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-quality", name = "enabled", havingValue = "true")
public class DataQualityFillTaskService {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PUBLIC_FAILURE_SUMMARY =
            "补全任务执行失败，请联系管理员处理";

    private final FillTaskRepository repository;
    private final FillTaskEvidenceCodec evidenceCodec;
    private final BuildingScopeService buildingScopeService;
    private final DataQualityRecoveryService recoveryService;

    /**
     * 按登录人的建筑范围在 MySQL 侧筛选和分页，避免先读取全量任务再在内存裁剪。
     */
    public IPage<DataQualityFillDtos.Response> page(
            Long userId,
            Collection<String> roles,
            int pageNum,
            int pageSize,
            String buildingId,
            String pointId,
            FillSourceType sourceType,
            Integer dataQuality,
            FillApplyStatus applyStatus,
            Long fromInclusive,
            Long toExclusive) {
        requireReader(roles);
        validateOptionalPeriod(fromInclusive, toExclusive);
        String normalizedBuilding = trimToNull(buildingId);
        String normalizedPoint = trimToNull(pointId);
        validateFilter(normalizedBuilding, "建筑ID");
        validateFilter(normalizedPoint, "测点ID");
        if (dataQuality != null && dataQuality != 1 && dataQuality != 2) {
            throw new BusinessException(400, "数据质量只能是1或2");
        }
        if (normalizedBuilding != null) {
            buildingScopeService.checkAccess(userId, roles, normalizedBuilding);
        }
        Set<String> scopes = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        try {
            return repository.findPage(
                            pageNum, pageSize, scopes == null, scopes,
                            normalizedBuilding, normalizedPoint, sourceType,
                            dataQuality, applyStatus, toLocal(fromInclusive), toLocal(toExclusive))
                    .convert(this::toResponse);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, exception.getMessage());
        }
    }

    /** 查询单条审计任务时先校验建筑权限，再解析可能损坏的冻结证据。 */
    public DataQualityFillDtos.Response detail(
            Long userId, Collection<String> roles, String taskId) {
        requireReader(roles);
        BizDataQualityFillTask task = requireTask(taskId);
        buildingScopeService.checkAccess(userId, roles, task.getBuildingId());
        return toResponse(task);
    }

    /** 平台管理员显式重试 FAILED；底层异常按即时外部资源不可用映射为 503。 */
    public DataQualityFillDtos.Response retry(
            Collection<String> roles, String taskId, long now) {
        requireAdmin(roles);
        BizDataQualityFillTask task = requireTask(taskId);
        if (task.getApplyStatus() != FillApplyStatus.FAILED) {
            throw new BusinessException(409, "只有 FAILED 补全任务允许重试");
        }
        try {
            recoveryService.recoverTask(taskId, now);
        } catch (IllegalStateException exception) {
            throw new BusinessException(409, exception.getMessage());
        } catch (RuntimeException exception) {
            throw new BusinessException(503, "TDengine 暂不可用，补全任务重试失败");
        }
        return toResponse(requireTask(taskId));
    }

    BizDataQualityFillTask requireTask(String taskId) {
        String normalized = trimToNull(taskId);
        if (normalized == null) {
            throw new BusinessException(400, "补全任务ID不能为空");
        }
        try {
            return repository.findAuditById(normalized)
                    .orElseThrow(() -> new BusinessException(404, "补全任务不存在"));
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalStateException | IllegalArgumentException exception) {
            // Repository 会在读取时校验 sourceType 与冻结证据；管理 API 将损坏任务
            // 归类为可人工修复的冲突，而不是把内部解析异常或堆栈暴露给调用方。
            throw new BusinessException(409, "补全任务证据已损坏，需人工修复");
        }
    }

    DataQualityFillDtos.Response toResponse(BizDataQualityFillTask task) {
        FillTaskEvidence evidence;
        try {
            evidence = evidenceCodec.decode(task.getSourceType(), task.getEvidenceJson());
        } catch (RuntimeException exception) {
            throw new BusinessException(409, "补全任务证据已损坏，需人工修复");
        }
        List<FillTaskEvidence.MinuteSegment> segments =
                evidence instanceof FillTaskEvidence.Typical typical
                        ? typical.appliedSegments()
                        : List.of(new FillTaskEvidence.MinuteSegment(
                                toEpoch(task.getStartMinute()),
                                toEpoch(task.getEndMinute())));
        return new DataQualityFillDtos.Response(
                task.getTaskId(), task.getIdempotencyKey(), task.getBuildingId(),
                task.getPointId(), toEpoch(task.getStartMinute()), toEpoch(task.getEndMinute()),
                task.getMinuteCount(), task.getDataQuality(), task.getSourceType(),
                task.getAlgorithmVersion(), evidence, segments, task.getTypicalConfigId(),
                task.getTypicalConfigVersion(), task.getApplyStatus(), task.getAppliedCount(),
                task.getFailedCount(), task.getReplacedCount(), task.getVoidedCount(),
                task.getRetryCount(), publicFailureSummary(task.getLastError()),
                toEpoch(task.getGeneratedAt()),
                toEpoch(task.getClosedAt()), task.getVoidBy(), task.getVoidReason(),
                toEpoch(task.getVoidAt()), task.getSupersedesTaskId(),
                toEpoch(task.getCreateTime()), toEpoch(task.getUpdateTime()));
    }

    /**
     * 数据库保留完整技术错误供服务端排查，管理 API 只公开固定业务摘要。
     * 这样 SQL、JDBC 地址、连接主机和堆栈片段不会通过审计响应泄露。
     */
    private String publicFailureSummary(String lastError) {
        return lastError == null || lastError.isBlank()
                ? null
                : PUBLIC_FAILURE_SUMMARY;
    }

    private void requireReader(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.BUILDING_OWNER)
                && !hasRole(roles, FormalRole.ENERGY_MANAGER)
                && !hasRole(roles, FormalRole.PLATFORM_ADMIN)) {
            throw new BusinessException(403, "当前角色无权读取补全任务");
        }
    }

    static void requireAdmin(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.PLATFORM_ADMIN)) {
            throw new BusinessException(403, "只有平台管理员可以执行数据修正");
        }
    }

    private static boolean hasRole(Collection<String> roles, FormalRole role) {
        return roles != null && roles.stream()
                .anyMatch(value -> role.name().equalsIgnoreCase(value));
    }

    private void validateOptionalPeriod(Long from, Long to) {
        if (from != null && to != null && from >= to) {
            throw new BusinessException(400, "查询结束时间必须晚于开始时间");
        }
    }

    private LocalDateTime toLocal(Long value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(value), PROJECT_ZONE);
        } catch (RuntimeException exception) {
            throw new BusinessException(400, "时间戳超出允许范围");
        }
    }

    private Long toEpoch(LocalDateTime value) {
        return value == null ? null
                : value.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateFilter(String value, String label) {
        if (value != null && value.length() > 32) {
            throw new BusinessException(400, label + "不能超过32个字符");
        }
    }
}
