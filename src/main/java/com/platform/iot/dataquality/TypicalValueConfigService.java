package com.platform.iot.dataquality;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.iot.dataquality.mapper.BizPointTypicalValueConfigMapper;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.dto.TypicalValueDtos;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Set;

/**
 * 典型值配置的版本创建、草稿维护和审批状态机。
 *
 * <p>能效管理员只在授权建筑内创建、修改和提交配置；平台管理员可维护全部建筑并负责审批和停用。
 * 创建版本时锁父测点后分配版本，批准时先锁配置行再锁父测点，保证并发操作不会产生重复版本
 * 或重叠的批准有效期。已批准记录不可直接修改，只能创建新版本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-quality", name = "enabled", havingValue = "true")
public class TypicalValueConfigService {

    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");

    private final BizPointTypicalValueConfigMapper configMapper;
    private final BizDataPointMapper pointMapper;
    private final BuildingScopeService buildingScopeService;
    private final TypicalValueConfigProvider provider;

    /**
     * 按登录人的建筑范围分页查询配置。
     *
     * <p>平台管理员以 {@code allBuildings=true} 查询全量；其他可读角色把授权建筑集合传入
     * Mapper，由 SQL 在分页前过滤，避免先查全量再在 Java 中裁剪造成数量和内容泄漏。</p>
     */
    public IPage<TypicalValueDtos.Response> page(
            Long userId,
            Collection<String> roles,
            int pageNum,
            int pageSize,
            String buildingId,
            String pointId,
            TypicalValueStatus status,
            Long validFrom,
            Long validTo) {
        requireReader(roles);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(400, "分页参数超出允许范围");
        }

        String normalizedBuildingId = trimToNull(buildingId);
        validateFilterLength(normalizedBuildingId, "建筑ID");
        String normalizedPointId = trimToNull(pointId);
        validateFilterLength(normalizedPointId, "测点ID");
        if (normalizedBuildingId != null) {
            buildingScopeService.checkAccess(userId, roles, normalizedBuildingId);
        }
        LocalDateTime from = toLocalDateTime(validFrom);
        LocalDateTime to = toLocalDateTime(validTo);
        validateOptionalPeriod(from, to);

        Set<String> accessibleBuildingIds =
                buildingScopeService.getAccessibleBuildingIds(userId, roles);
        IPage<BizPointTypicalValueConfig> result = configMapper.selectPageFiltered(
                new Page<>(pageNum, pageSize),
                accessibleBuildingIds == null,
                accessibleBuildingIds,
                normalizedBuildingId,
                normalizedPointId,
                status == null ? null : status.name(),
                from,
                to);
        return result.convert(this::toResponse);
    }

    /** 查询单条配置时仍校验记录所属建筑，不能凭配置 ID 绕过数据范围。 */
    public TypicalValueDtos.Response detail(
            Long userId,
            Collection<String> roles,
            String configId) {
        requireReader(roles);
        BizPointTypicalValueConfig config = configMapper.selectById(
                requireText(configId, "典型值配置ID不能为空"));
        if (config == null) {
            throw new BusinessException(404, "典型值配置不存在");
        }
        buildingScopeService.checkAccess(userId, roles, config.getBuildingId());
        return toResponse(config);
    }

    /** API 创建入口：由 Service 统一把 Unix 毫秒转换为 MySQL 本地时间。 */
    @Transactional(rollbackFor = Exception.class)
    public TypicalValueDtos.Response createView(
            Long userId,
            Collection<String> roles,
            TypicalValueDtos.CreateRequest request) {
        return toResponse(create(
                userId,
                roles,
                request.pointId(),
                request.typicalValue(),
                request.sourceDescription(),
                request.reason(),
                requiredLocalDateTime(request.validFrom()),
                toLocalDateTime(request.validTo())));
    }

    /** API 修改入口：只修改草稿业务字段，不接受状态和审计字段。 */
    @Transactional(rollbackFor = Exception.class)
    public TypicalValueDtos.Response updateView(
            Long userId,
            Collection<String> roles,
            String configId,
            TypicalValueDtos.UpdateRequest request) {
        return toResponse(update(
                userId,
                roles,
                configId,
                request.typicalValue(),
                request.sourceDescription(),
                request.reason(),
                requiredLocalDateTime(request.validFrom()),
                toLocalDateTime(request.validTo())));
    }

    /** API 提交入口，返回稳定的响应 DTO。 */
    @Transactional(rollbackFor = Exception.class)
    public TypicalValueDtos.Response submitView(
            Long userId,
            Collection<String> roles,
            String configId) {
        return toResponse(submit(userId, roles, configId));
    }

    /** API 批准入口，只有平台管理员角色可通过底层状态机校验。 */
    @Transactional(rollbackFor = Exception.class)
    public TypicalValueDtos.Response approveView(
            Long reviewerId,
            Collection<String> roles,
            String configId,
            TypicalValueDtos.ReviewRequest request) {
        return toResponse(approve(reviewerId, roles, configId, request.comment()));
    }

    /** API 拒绝入口，审核意见由 DTO 和状态机双重校验。 */
    @Transactional(rollbackFor = Exception.class)
    public TypicalValueDtos.Response rejectView(
            Long reviewerId,
            Collection<String> roles,
            String configId,
            TypicalValueDtos.ReviewRequest request) {
        return toResponse(reject(reviewerId, roles, configId, request.comment()));
    }

    /** API 停用入口，历史任务仍保留原典型值版本证据。 */
    @Transactional(rollbackFor = Exception.class)
    public TypicalValueDtos.Response disableView(
            Long operatorId,
            Collection<String> roles,
            String configId,
            TypicalValueDtos.DisableRequest request) {
        return toResponse(disable(operatorId, roles, configId, request.reason()));
    }

    /**
     * 创建新的草稿版本。
     *
     * <p>先锁父测点再读取最大版本号，使同一测点的并发创建在数据库中串行执行。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public BizPointTypicalValueConfig create(
            Long userId,
            Collection<String> roles,
            String pointId,
            BigDecimal typicalValue,
            String sourceDescription,
            String reason,
            LocalDateTime validFrom,
            LocalDateTime validTo) {
        requireMaintainer(roles);
        requireText(pointId, "测点不能为空");
        validatePeriod(validFrom, validTo);

        BizDataPoint point = requireEligiblePoint(pointMapper.selectByIdForUpdate(pointId));
        buildingScopeService.checkAccess(userId, roles, point.getBuildingId());
        validateValue(point, typicalValue);

        BizPointTypicalValueConfig config = new BizPointTypicalValueConfig();
        config.setPointId(point.getPointId());
        config.setBuildingId(point.getBuildingId());
        config.setTypicalValue(typicalValue);
        // 单位从测点档案冻结，调用方不能通过请求伪造单位。
        config.setUnit(point.getUnit());
        config.setSourceDescription(requireText(sourceDescription, "典型值来源说明不能为空"));
        config.setReason(requireText(reason, "典型值使用原因不能为空"));
        config.setValidFrom(validFrom);
        config.setValidTo(validTo);
        config.setStatus(TypicalValueStatus.DRAFT);
        config.setVersion(configMapper.selectMaxVersion(pointId) + 1);
        config.setCreatedBy(userId);
        configMapper.insert(config);
        return config;
    }

    /** 只有草稿可修改；批准配置需要创建新版本以保留完整审计依据。 */
    @Transactional(rollbackFor = Exception.class)
    public BizPointTypicalValueConfig update(
            Long userId,
            Collection<String> roles,
            String configId,
            BigDecimal typicalValue,
            String sourceDescription,
            String reason,
            LocalDateTime validFrom,
            LocalDateTime validTo) {
        requireMaintainer(roles);
        BizPointTypicalValueConfig config = requireLockedConfig(configId);
        // 先校验建筑范围，再暴露状态冲突，避免配置 ID 被猜中后泄露其他建筑的审批状态。
        buildingScopeService.checkAccess(userId, roles, config.getBuildingId());
        requireStatus(config, TypicalValueStatus.DRAFT, "只有草稿典型值可以修改");
        validatePeriod(validFrom, validTo);

        BizDataPoint point = requireEligiblePoint(pointMapper.selectById(config.getPointId()));
        validatePointOwnership(config, point);
        validateValue(point, typicalValue);
        config.setTypicalValue(typicalValue);
        config.setUnit(point.getUnit());
        config.setSourceDescription(requireText(sourceDescription, "典型值来源说明不能为空"));
        config.setReason(requireText(reason, "典型值使用原因不能为空"));
        config.setValidFrom(validFrom);
        config.setValidTo(validTo);
        configMapper.updateById(config);
        return config;
    }

    /** 将草稿提交为待审；提交后内容冻结，避免管理员审核期间证据被修改。 */
    @Transactional(rollbackFor = Exception.class)
    public BizPointTypicalValueConfig submit(
            Long userId,
            Collection<String> roles,
            String configId) {
        requireMaintainer(roles);
        BizPointTypicalValueConfig config = requireLockedConfig(configId);
        // 与修改入口保持相同顺序：越权始终返回 403，不泄露目标配置状态。
        buildingScopeService.checkAccess(userId, roles, config.getBuildingId());
        requireStatus(config, TypicalValueStatus.DRAFT, "只有草稿典型值可以提交");
        config.setStatus(TypicalValueStatus.PENDING);
        config.setSubmittedAt(LocalDateTime.now(MYSQL_ZONE));
        configMapper.updateById(config);
        return config;
    }

    /**
     * 批准待审配置。
     *
     * <p>锁顺序固定为配置行→父测点行。父测点锁覆盖同一测点的所有候选配置，使重叠检查直到
     * 事务提交都保持有效；反向加锁会与创建版本路径形成不一致锁序并增加死锁风险。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public BizPointTypicalValueConfig approve(
            Long reviewerId,
            Collection<String> roles,
            String configId,
            String comment) {
        requirePlatformAdmin(roles);
        BizPointTypicalValueConfig config = requireLockedConfig(configId);
        requireStatus(config, TypicalValueStatus.PENDING, "只有待审典型值可以批准");
        if (reviewerId != null && reviewerId.equals(config.getCreatedBy())) {
            throw new BusinessException(409, "创建人不能批准自己创建的典型值配置");
        }

        BizDataPoint point = requireEligiblePoint(pointMapper.selectByIdForUpdate(config.getPointId()));
        validatePointOwnership(config, point);
        validatePeriod(config.getValidFrom(), config.getValidTo());
        validateValue(point, config.getTypicalValue());
        if (configMapper.existsApprovedOverlap(
                config.getPointId(),
                config.getValidFrom(),
                config.getValidTo(),
                config.getConfigId())) {
            throw new BusinessException(409, "典型值有效期与已批准配置重叠");
        }

        config.setStatus(TypicalValueStatus.APPROVED);
        config.setReviewerId(reviewerId);
        config.setReviewComment(trimToNull(comment));
        config.setReviewedAt(LocalDateTime.now(MYSQL_ZONE));
        configMapper.updateById(config);
        refreshSnapshotAfterCommit();
        return config;
    }

    /** 拒绝待审配置，审核意见必须说明原因，便于创建人创建后续版本。 */
    @Transactional(rollbackFor = Exception.class)
    public BizPointTypicalValueConfig reject(
            Long reviewerId,
            Collection<String> roles,
            String configId,
            String comment) {
        requirePlatformAdmin(roles);
        String requiredComment = requireText(comment, "拒绝原因不能为空");
        BizPointTypicalValueConfig config = requireLockedConfig(configId);
        requireStatus(config, TypicalValueStatus.PENDING, "只有待审典型值可以拒绝");
        config.setStatus(TypicalValueStatus.REJECTED);
        config.setReviewerId(reviewerId);
        config.setReviewComment(requiredComment);
        config.setReviewedAt(LocalDateTime.now(MYSQL_ZONE));
        configMapper.updateById(config);
        return config;
    }

    /**
     * 停用已批准配置。
     *
     * <p>停用只阻止后续分钟匹配，不删除配置，也不撤销已经据此生成的历史任务。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public BizPointTypicalValueConfig disable(
            Long operatorId,
            Collection<String> roles,
            String configId,
            String reason) {
        requirePlatformAdmin(roles);
        String requiredReason = requireText(reason, "停用原因不能为空");
        BizPointTypicalValueConfig config = requireLockedConfig(configId);
        requireStatus(config, TypicalValueStatus.APPROVED, "只有已批准典型值可以停用");
        config.setStatus(TypicalValueStatus.DISABLED);
        config.setDisabledBy(operatorId);
        config.setDisabledReason(requiredReason);
        config.setDisabledAt(LocalDateTime.now(MYSQL_ZONE));
        configMapper.updateById(config);
        refreshSnapshotAfterCommit();
        return config;
    }

    private BizPointTypicalValueConfig requireLockedConfig(String configId) {
        return configMapper.selectByIdForUpdate(configId)
                .orElseThrow(() -> new BusinessException(404, "典型值配置不存在"));
    }

    private static BizDataPoint requireEligiblePoint(BizDataPoint point) {
        if (point == null) {
            throw new BusinessException(404, "测点不存在");
        }
        if (!"ONLINE".equalsIgnoreCase(point.getStatus())
                || !"ANALOG".equalsIgnoreCase(point.getDataType())
                || !Integer.valueOf(1).equals(point.getIsForCalc())) {
            throw new BusinessException(409, "测点不满足典型值补全条件");
        }
        if (point.getBuildingId() == null || point.getBuildingId().isBlank()) {
            throw new BusinessException(409, "测点未绑定建筑");
        }
        if (point.getUnit() == null || point.getUnit().isBlank()) {
            throw new BusinessException(409, "测点未配置单位，不能冻结典型值依据");
        }
        return point;
    }

    private static void validatePointOwnership(
            BizPointTypicalValueConfig config,
            BizDataPoint point) {
        if (!config.getPointId().equals(point.getPointId())
                || !config.getBuildingId().equals(point.getBuildingId())) {
            throw new BusinessException(409, "典型值配置与测点档案不一致");
        }
    }

    private static void validateValue(BizDataPoint point, BigDecimal value) {
        if (value == null) {
            throw new BusinessException(400, "典型值不能为空");
        }
        if ((point.getValueMin() != null && value.compareTo(point.getValueMin()) < 0)
                || (point.getValueMax() != null && value.compareTo(point.getValueMax()) > 0)) {
            throw new BusinessException(400, "典型值超出测点量程");
        }
    }

    private static void validatePeriod(LocalDateTime validFrom, LocalDateTime validTo) {
        if (validFrom == null) {
            throw new BusinessException(400, "典型值生效时间不能为空");
        }
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new BusinessException(400, "典型值失效时间必须晚于生效时间");
        }
    }

    private static void validateOptionalPeriod(
            LocalDateTime validFrom,
            LocalDateTime validTo) {
        if (validFrom != null && validTo != null && !validTo.isAfter(validFrom)) {
            throw new BusinessException(400, "查询结束时间必须晚于开始时间");
        }
    }

    private static void requireStatus(
            BizPointTypicalValueConfig config,
            TypicalValueStatus expected,
            String message) {
        if (config.getStatus() != expected) {
            throw new BusinessException(409, message);
        }
    }

    private static void requireMaintainer(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.ENERGY_MANAGER)
                && !hasRole(roles, FormalRole.PLATFORM_ADMIN)) {
            throw new BusinessException(403, "只有能效管理员或平台管理员可以维护典型值配置");
        }
    }

    private static void requireReader(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.BUILDING_OWNER)
                && !hasRole(roles, FormalRole.ENERGY_MANAGER)
                && !hasRole(roles, FormalRole.PLATFORM_ADMIN)) {
            throw new BusinessException(403, "当前角色无权读取典型值配置");
        }
    }

    private static void requirePlatformAdmin(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.PLATFORM_ADMIN)) {
            throw new BusinessException(403, "只有平台管理员可以审核或停用典型值配置");
        }
    }

    private static boolean hasRole(Collection<String> roles, FormalRole expected) {
        return roles != null && roles.stream()
                .anyMatch(role -> expected.name().equalsIgnoreCase(role));
    }

    private static String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(400, message);
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void validateFilterLength(String value, String field) {
        if (value != null && value.length() > 32) {
            throw new BusinessException(400, field + "不能超过32个字符");
        }
    }

    private static LocalDateTime requiredLocalDateTime(Long epochMillis) {
        LocalDateTime value = toLocalDateTime(epochMillis);
        if (value == null) {
            throw new BusinessException(400, "典型值生效时间不能为空");
        }
        return value;
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(epochMillis)
                    .atZone(MYSQL_ZONE)
                    .toLocalDateTime();
        } catch (RuntimeException exception) {
            throw new BusinessException(400, "时间戳超出允许范围");
        }
    }

    private TypicalValueDtos.Response toResponse(BizPointTypicalValueConfig config) {
        return new TypicalValueDtos.Response(
                config.getConfigId(),
                config.getPointId(),
                config.getBuildingId(),
                config.getTypicalValue(),
                config.getUnit(),
                config.getSourceDescription(),
                config.getReason(),
                toEpochMillis(config.getValidFrom()),
                toEpochMillis(config.getValidTo()),
                config.getStatus(),
                config.getVersion(),
                config.getCreatedBy(),
                toEpochMillis(config.getSubmittedAt()),
                config.getReviewerId(),
                config.getReviewComment(),
                toEpochMillis(config.getReviewedAt()),
                config.getDisabledBy(),
                config.getDisabledReason(),
                toEpochMillis(config.getDisabledAt()),
                toEpochMillis(config.getCreateTime()),
                toEpochMillis(config.getUpdateTime()));
    }

    private static Long toEpochMillis(LocalDateTime value) {
        return value == null ? null : value.atZone(MYSQL_ZONE).toInstant().toEpochMilli();
    }

    private void refreshSnapshotAfterCommit() {
        Runnable refresh = () -> {
            try {
                provider.refresh();
            } catch (RuntimeException exception) {
                // 数据库状态已经提交时，缓存刷新失败不能把成功审批伪装成失败；定时刷新会继续补偿。
                log.warn("典型值配置已提交，但运行时快照刷新失败", exception);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            refresh.run();
                        }
                    });
        } else {
            refresh.run();
        }
    }
}
