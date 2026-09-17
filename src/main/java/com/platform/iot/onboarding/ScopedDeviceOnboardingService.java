package com.platform.iot.onboarding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.sensitive.SensitiveChangeStatus;
import com.platform.audit.system.BindTypedPendingDeviceHandler;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.entity.BizDataSource;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizSpaceMapper;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.platform.iot.onboarding.OnboardingErrors.*;

/** 运维增量入口的权限边界：先限制菜单和厂家项目范围，再调用既有管理领域服务。 */
@Service
@RequiredArgsConstructor
public class ScopedDeviceOnboardingService {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final Set<String> MENU_PATHS = Set.of("/system/device-onboarding",
            "/configuration/ingestion/pendingDevices", "/operations/devices/pendingDevices");
    private final BuildingScopeService buildingScope;
    private final SysMenuMapper menuMapper;
    private final DaikinDirectoryService directory;
    private final BizPendingDeviceMapper pendingMapper;
    private final DeviceOnboardingService onboarding;
    private final DeviceProductService products;
    private final BizDataSourceMapper dataSources;
    private final BuildingMapper buildings;
    private final BizSpaceMapper spaces;
    private final BizSystemGroupMapper systemGroups;
    private final BizEquipmentMapper equipment;
    private final BizDataPointMapper points;
    private final BackendDutyService duties;
    private final SensitiveChangeService changes;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public PageResponse<DeviceOnboardingContracts.PendingListItemView> list(
            Long userId, Set<String> roles, int page, int size, String status) {
        requireMenu(userId, roles);
        if (page < 1 || size < 1 || size > 100) throw error(400, VALIDATION_FAILED, "分页参数超出允许范围");
        var query = new LambdaQueryWrapper<BizPendingDevice>().eq(BizPendingDevice::getIdentityType, "DAIKIN_UNIT");
        Set<String> buildings = buildingScope.getAccessibleBuildingIds(userId, roles);
        if (buildings != null) {
            if (buildings.isEmpty()) return new PageResponse<>(page, size, 0, List.of());
            applyBuildings(query, buildings);
        }
        if (status != null && !status.isBlank()) {
            if (!Set.of("DISCOVERED", "IGNORED", "BOUND").contains(status)) {
                throw error(400, VALIDATION_FAILED, "无效待接入状态");
            }
            query.eq(BizPendingDevice::getStatus, status);
        }
        query.orderByDesc(BizPendingDevice::getLastSeenTime).orderByAsc(BizPendingDevice::getPendingId);
        var result = pendingMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords().stream()
                .map(p -> new DeviceOnboardingContracts.PendingListItemView(p.getPendingId(), p.getIdentityType(),
                        "****", p.getProfileCode(), p.getLastProfileVersion(), p.getStatus(), p.getReportCount(),
                        p.getFirstSeenTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                        p.getLastSeenTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                        Integer.valueOf(1).equals(p.getSampleTruncated()))).toList());
    }

    @Transactional
    public DeviceOnboardingContracts.PendingDetailView detail(Long userId, Set<String> roles, String pendingId) {
        requirePendingAccess(userId, roles, pendingId);
        return onboarding.pendingDetail(pendingId, ADMIN);
    }

    @Transactional
    public DirectoryDetail directoryDetail(Long userId, Set<String> roles, String pendingId) {
        requirePendingAccess(userId, roles, pendingId);
        return new DirectoryDetail(onboarding.pendingDetail(pendingId, ADMIN), directory.detail(pendingId));
    }

    @Transactional
    public PageResponse<com.platform.iot.onboarding.api.DeviceProductContracts.ListItemView> products(
            Long userId, Set<String> roles, String pendingId, int page, int size) {
        var pending = detail(userId, roles, pendingId);
        return products.list(page, size, "ENABLED", null, pending.profileCode(), pending.identityType(), ADMIN);
    }

    /** 先校验待接入设备的厂家项目范围，再只返回状态、协议和身份类型均兼容的产品详情。 */
    @Transactional
    public com.platform.iot.onboarding.api.DeviceProductContracts.DetailView product(
            Long userId, Set<String> roles, String pendingId, String productId) {
        var pending = detail(userId, roles, pendingId);
        var product = products.detail(productId, ADMIN);
        if (!"ENABLED".equals(product.status()) || !pending.profileCode().equals(product.expectedProfileCode())
                || !pending.identityType().equals(product.identityType())) {
            throw error(404, NOT_FOUND, "兼容产品不存在");
        }
        return product;
    }

    /** 仅列出映射建筑内已启用的 HTTP 数值来源供温度绑定选择，不返回厂家连接设置或凭据。 */
    @Transactional
    public List<NumericSource> numericSources(Long userId, Set<String> roles, String pendingId) {
        requirePendingAccess(userId, roles, pendingId);
        String buildingId = directory.requireMappedBuilding(pendingId);
        return dataSources.selectList(new LambdaQueryWrapper<BizDataSource>()
                        .eq(BizDataSource::getBuildingId, buildingId)
                        .eq(BizDataSource::getTransportType, "HTTP")
                        .eq(BizDataSource::getStatus, "ENABLED")
                        .orderByAsc(BizDataSource::getSourceName)
                        .orderByAsc(BizDataSource::getSourceId))
                .stream().map(source -> new NumericSource(source.getSourceId(), source.getSourceCode(),
                        source.getSourceName())).toList();
    }

    /** 返回厂家映射建筑内的绑定候选，不调用管理员资产 API，也不包含其他建筑档案。 */
    @Transactional
    public BindingOptions bindingOptions(Long userId, Set<String> roles, String pendingId,
            int page, int size, String spaceId, String systemGroupId) {
        requirePendingAccess(userId, roles, pendingId);
        if (page < 1 || size < 1 || size > 100) throw error(400, VALIDATION_FAILED, "设备候选分页参数无效");
        String buildingId = directory.requireMappedBuilding(pendingId);
        var building = buildings.selectById(buildingId);
        if (building == null) throw error(404, NOT_FOUND, "厂家项目映射建筑不存在");
        var spaceViews = spaces.selectList(new LambdaQueryWrapper<BizSpace>()
                        .eq(BizSpace::getBuildingId, buildingId).orderByAsc(BizSpace::getFloorLevel)
                        .orderByAsc(BizSpace::getSpaceId)).stream()
                .map(value -> new SpaceOption(value.getSpaceId(), value.getParentSpaceId(), value.getSpaceName())).toList();
        var systemViews = systemGroups.selectList(new LambdaQueryWrapper<BizSystemGroup>()
                        .eq(BizSystemGroup::getBuildingId, buildingId).orderByAsc(BizSystemGroup::getSystemGroupName)
                        .orderByAsc(BizSystemGroup::getSystemGroupId)).stream()
                .map(value -> new SystemOption(value.getSystemGroupId(), value.getSystemGroupName())).toList();
        var equipmentQuery = new LambdaQueryWrapper<BizEquipment>().eq(BizEquipment::getBuildingId, buildingId);
        if (spaceId != null && !spaceId.isBlank()) equipmentQuery.eq(BizEquipment::getSpaceId, spaceId);
        if (systemGroupId != null && !systemGroupId.isBlank()) {
            equipmentQuery.eq(BizEquipment::getSystemGroupId, systemGroupId);
        }
        var equipmentPage = equipment.selectPage(new Page<BizEquipment>(page, size), equipmentQuery
                .orderByAsc(BizEquipment::getEquipName).orderByAsc(BizEquipment::getEquipId));
        var equipmentRows = equipmentPage.getRecords();
        List<String> equipmentIds = equipmentRows.stream().map(BizEquipment::getEquipId).toList();
        Map<String, List<PointOption>> pointViews = equipmentIds.isEmpty() ? Map.of()
                : points.selectList(new LambdaQueryWrapper<BizDataPoint>().in(BizDataPoint::getEquipId, equipmentIds)
                        .orderByAsc(BizDataPoint::getPointName).orderByAsc(BizDataPoint::getPointId)).stream()
                .collect(Collectors.groupingBy(BizDataPoint::getEquipId,
                        Collectors.mapping(point -> new PointOption(point.getPointId(), point.getPointCode(),
                                point.getPointName()), Collectors.toList())));
        var equipmentViews = equipmentRows.stream().map(value -> new EquipmentOption(value.getEquipId(),
                value.getEquipName(), value.getSpaceId(), value.getSystemGroupId(),
                pointViews.getOrDefault(value.getEquipId(), List.of()))).toList();
        return new BindingOptions(buildingId, building.getBuildingName(), spaceViews, systemViews,
                equipmentPage.getCurrent(), equipmentPage.getSize(), equipmentPage.getTotal(), equipmentViews);
    }

    @Transactional
    public DeviceOnboardingContracts.ConnectionView connection(Long userId, Set<String> roles, String pendingId) {
        requirePendingAccess(userId, roles, pendingId);
        return onboarding.connection(pendingId, ADMIN);
    }

    @Transactional
    public DeviceOnboardingContracts.PendingDetailView updateStatus(Long userId, Set<String> roles, String pendingId,
            DeviceOnboardingContracts.PendingStatusRequest request) {
        requirePendingAccess(userId, roles, pendingId);
        duties.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        return onboarding.updatePendingStatus(pendingId, request, userId, ADMIN);
    }

    /** 每台独立申请；幂等键由调用者稳定保存，重试不会再建草稿，也不把部分成功称为整批成功。 */
    public BindingApplication apply(Long userId, Set<String> roles, String pendingId,
            DeviceOnboardingContracts.TypedBindRequest binding, String idempotencyKey) {
        return transaction.execute(status -> applyInTransaction(userId, roles, pendingId, binding, idempotencyKey));
    }

    private BindingApplication applyInTransaction(Long userId, Set<String> roles, String pendingId,
            DeviceOnboardingContracts.TypedBindRequest binding, String idempotencyKey) {
        requirePendingAccess(userId, roles, pendingId);
        duties.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        String building = onboarding.resolveTypedBindBuilding(pendingId, binding, ADMIN);
        buildingScope.checkAccess(userId, roles, building);
        var draft = changes.createDraft(userId, BindTypedPendingDeviceHandler.CODE,
                mapper.valueToTree(new BindTypedPendingDeviceHandler.Command(pendingId, binding)), idempotencyKey);
        var submitted = draft.status() == SensitiveChangeStatus.DRAFT ? changes.submit(userId, draft.requestId()) : draft;
        return new BindingApplication(pendingId, submitted.requestId(), submitted.status().name(), null);
    }

    public List<BindingApplication> applyBatch(Long userId, Set<String> roles, List<BindingItem> items) {
        requireMenu(userId, roles);
        duties.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        if (items == null || items.isEmpty() || items.size() > 50) {
            throw error(400, VALIDATION_FAILED, "批量绑定申请应为 1 至 50 台");
        }
        if (items.stream().anyMatch(item -> item == null || item.pendingId() == null || item.binding() == null
                || item.idempotencyKey() == null || item.idempotencyKey().isBlank())) {
            throw error(400, VALIDATION_FAILED, "批量绑定申请包含缺失字段");
        }
        return items.stream().map(item -> {
            try {
                return apply(userId, roles, item.pendingId(), item.binding(), item.idempotencyKey());
            } catch (BusinessException failure) {
                // 不把后端原始异常或跨范围设备信息带入逐项结果。
                boolean conflict = Integer.valueOf(409).equals(failure.getCode());
                return new BindingApplication(item.pendingId(), null, conflict ? "CONFLICT" : "FAILED",
                        conflict ? "STATE_CONFLICT" : "REQUEST_REJECTED");
            }
        }).toList();
    }

    /** 运维只提交既有身份启停审批；身份归属和建筑范围从当前待接入记录及连接关系解析。 */
    @Transactional
    public BindingApplication requestIdentityStatus(Long userId, Set<String> roles, String pendingId,
            String targetStatus, String idempotencyKey) {
        requirePendingAccess(userId, roles, pendingId);
        duties.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        var connection = onboarding.connection(pendingId, ADMIN);
        if (connection.identityId() == null) throw error(409, STATE_CONFLICT, "待接入设备尚未生成身份");
        buildingScope.checkAccess(userId, roles, connection.buildingId());
        String operation = switch (targetStatus) {
            case "ACTIVE" -> "ACTIVATE_DEVICE_IDENTITY";
            case "INACTIVE" -> "DEACTIVATE_DEVICE_IDENTITY";
            default -> throw error(400, VALIDATION_FAILED, "无效身份目标状态");
        };
        var draft = changes.createDraft(userId, operation,
                mapper.valueToTree(java.util.Map.of("identityId", connection.identityId())), idempotencyKey);
        var submitted = draft.status() == SensitiveChangeStatus.DRAFT ? changes.submit(userId, draft.requestId()) : draft;
        return new BindingApplication(pendingId, submitted.requestId(), submitted.status().name(), null);
    }

    private void requirePendingAccess(Long userId, Set<String> roles, String pendingId) {
        requireMenu(userId, roles);
        if (roles.contains("PLATFORM_ADMIN")) {
            if (!directory.isDirectoryPending(pendingId)) throw error(404, NOT_FOUND, "厂家待接入设备不存在");
            return;
        }
        // 先使用可见 ID 集合，未知项目和不存在的 ID 对运维均返回同一结果。
        var buildings = buildingScope.getAccessibleBuildingIds(userId, roles);
        if (buildings == null || buildings.isEmpty()) throw error(403, FORBIDDEN, "无权访问该待接入设备");
        var query = new LambdaQueryWrapper<BizPendingDevice>().eq(BizPendingDevice::getPendingId, pendingId)
                .eq(BizPendingDevice::getIdentityType, "DAIKIN_UNIT");
        applyBuildings(query, buildings);
        if (pendingMapper.selectCount(query) != 1) throw error(403, FORBIDDEN, "无权访问该待接入设备");
        // 在同一请求事务中锁定映射并复查，避免查询通过后项目被迁移导致详情或状态写入串楼。
        String mappedBuilding = directory.requireMappedBuilding(pendingId);
        directory.requireBinding(pendingId, mappedBuilding, pendingMapper.selectById(pendingId).getProfileCode());
        buildingScope.checkAccess(userId, roles, mappedBuilding);
    }

    private static void applyBuildings(LambdaQueryWrapper<BizPendingDevice> query, Set<String> buildings) {
        if (buildings.size() > 1000) throw error(400, VALIDATION_FAILED, "建筑授权数量超出单次查询上限");
        var values = buildings.stream().sorted().toList();
        String placeholders = java.util.stream.IntStream.range(0, values.size())
                .mapToObj(i -> "{" + i + "}").collect(java.util.stream.Collectors.joining(","));
        // 由后端授权集生成占位符，值仍由 MyBatis 绑定；数量和行数据使用完全相同的范围谓词。
        query.apply("EXISTS (SELECT 1 FROM biz_daikin_directory d JOIN biz_daikin_project_mapping m "
                + "ON m.source_id=d.source_id AND m.site_id=d.site_id "
                + "WHERE d.pending_id=biz_pending_device.pending_id AND m.building_id IN (" + placeholders + "))",
                values.toArray());
    }

    private void requireMenu(Long userId, Set<String> roles) {
        if (userId == null || roles == null) throw error(403, FORBIDDEN, "缺少登录身份");
        if (roles.contains("PLATFORM_ADMIN")) return;
        if (menuMapper.selectVisibleMenusByUserId(userId).stream()
                .noneMatch(menu -> "C".equals(menu.getMenuType()) && MENU_PATHS.contains(menu.getPath()))) {
            throw error(403, FORBIDDEN, "缺少待接入设备菜单授权");
        }
    }

    public record BindingApplication(String pendingId, String requestId, String status, String errorCode) { }
    public record DirectoryDetail(DeviceOnboardingContracts.PendingDetailView pending,
                                  DaikinDirectoryService.DirectoryView directory) { }
    public record BindingItem(String pendingId, DeviceOnboardingContracts.TypedBindRequest binding, String idempotencyKey) { }
    public record NumericSource(String sourceId, String sourceCode, String sourceName) { }
    public record BindingOptions(String buildingId, String buildingName, List<SpaceOption> spaces,
                                 List<SystemOption> systems, long equipmentPage, long equipmentSize,
                                 long equipmentTotal, List<EquipmentOption> equipment) { }
    public record SpaceOption(String spaceId, String parentSpaceId, String spaceName) { }
    public record SystemOption(String systemGroupId, String systemName) { }
    public record EquipmentOption(String equipmentId, String equipmentName, String spaceId,
                                  String systemGroupId, List<PointOption> points) { }
    public record PointOption(String pointId, String pointCode, String pointName) { }
}
