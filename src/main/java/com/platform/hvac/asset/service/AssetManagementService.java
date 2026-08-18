package com.platform.hvac.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.asset.api.AssetManagementContracts;
import com.platform.hvac.mapper.BizDeviceIdentityMapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizDeviceIdentity;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizPointAlias;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BizEquipmentService;
import com.platform.hvac.service.BizSpaceService;
import com.platform.hvac.service.BizSystemGroupService;
import com.platform.hvac.service.BuildingService;
import com.platform.iot.onboarding.mapper.BizDeviceProductMapper;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.mapper.BizProductPointTemplateMapper;
import com.platform.iot.onboarding.model.entity.BizDeviceProduct;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import com.platform.iot.onboarding.model.entity.BizProductPointTemplate;
import com.platform.system.mapper.SysUserBuildingMapper;
import com.platform.system.model.entity.SysUserBuilding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.platform.hvac.asset.api.AssetManagementContracts.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 版本化资产管理 API 的应用服务。
 *
 * <p>本服务把既有 HVAC 档案 Service 的实体结果装配为稳定 DTO，并在逻辑删除前显式统计
 * 子空间、设备、测点、身份、别名和授权引用。写操作再次校验平台管理员角色，不能只依赖
 * Controller 注解；本服务只访问 MySQL 配置，不读取或删除 TDengine 时序事实。</p>
 */
public class AssetManagementService {
    private static final List<String> EDIT_ACTIONS = List.of("UPDATE", "DELETE");
    private static final List<String> PARENT_EDIT_ACTIONS = List.of("CREATE", "UPDATE", "DELETE");

    private final BuildingService buildingService;
    private final BizSpaceService spaceService;
    private final BizSystemGroupService systemGroupService;
    private final BizEquipmentService equipmentService;
    private final BizDataPointService dataPointService;
    private final BizDeviceIdentityMapper identityMapper;
    private final BizPointAliasMapper aliasMapper;
    private final BizDeviceProductMapper productMapper;
    private final BizProductPointTemplateMapper productPointMapper;
    private final BizPendingDeviceMapper pendingMapper;
    private final SysUserBuildingMapper userBuildingMapper;

    public PageResponse<BuildingView> listBuildings(
            int page, int size, String keyword, Collection<String> roles) {
        requireAdmin(roles);
        validatePage(page, size);
        IPage<Building> result = buildingService.list(page, size, keyword, null).getData();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::buildingView).toList());
    }

    public BuildingView buildingDetail(String buildingId, Collection<String> roles) {
        requireAdmin(roles);
        return buildingView(requireBuilding(buildingId));
    }

    @Transactional
    public BuildingView createBuilding(BuildingCreateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        requireActiveStatus(request.status());
        Building building = new Building();
        applyBuilding(request.buildingName(), request.buildingCode(), request.buildingType(),
                request.constructionYear(), request.totalGfa(), request.climateZone(), building);
        return buildingView(call(() -> buildingService.add(building).getData()));
    }

    @Transactional
    public BuildingView updateBuilding(
            String buildingId, BuildingUpdateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        requireBuilding(buildingId);
        requireActiveStatus(request.status());
        Building building = new Building();
        building.setBuildingId(buildingId);
        applyBuilding(request.buildingName(), request.buildingCode(), request.buildingType(),
                request.constructionYear(), request.totalGfa(), request.climateZone(), building);
        call(() -> buildingService.update(building).getData());
        return buildingView(requireBuilding(buildingId));
    }

    @Transactional
    public void deleteBuilding(String buildingId, Collection<String> roles) {
        requireAdmin(roles);
        requireBuilding(buildingId);
        References references = buildingReferences(buildingId);
        if (references.spaces() + references.systemGroups() + references.equipment()
                + references.points() + references.authorizations() > 0) {
            throw AssetErrors.error(409, AssetErrors.REFERENCE_CONFLICT,
                    "建筑仍被空间、系统、设备、测点或用户授权引用，不能删除");
        }
        call(() -> buildingService.delete(buildingId).getData());
    }

    public List<SpaceView> listSpaces(String buildingId, Collection<String> roles) {
        requireAdmin(roles);
        requireBuilding(buildingId);
        List<BizSpace> spaces = spaceService.listByBuilding(buildingId).getData();
        Map<String, List<BizSpace>> byParent = new HashMap<>();
        Set<String> ids = new HashSet<>();
        spaces.forEach(space -> ids.add(space.getSpaceId()));
        spaces.forEach(space -> {
            String parent = ids.contains(space.getParentSpaceId()) ? space.getParentSpaceId() : null;
            byParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(space);
        });
        return byParent.getOrDefault(null, List.of()).stream()
                .sorted(spaceComparator())
                .map(space -> spaceView(space, byParent, new HashSet<>()))
                .toList();
    }

    @Transactional
    public SpaceView createSpace(SpaceCreateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        requireBuilding(request.buildingId());
        requireActiveStatus(request.status());
        BizSpace space = new BizSpace();
        space.setBuildingId(request.buildingId());
        applySpace(request.parentSpaceId(), request.spaceName(), request.spaceCode(),
                request.spaceType(), request.sortOrder(), request.usableArea(), space);
        BizSpace saved = call(() -> spaceService.add(space).getData());
        return spaceView(saved, Map.of(), new HashSet<>());
    }

    @Transactional
    public SpaceView updateSpace(
            String spaceId, SpaceUpdateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        BizSpace existing = requireSpace(spaceId);
        requireActiveStatus(request.status());
        validateSpaceAncestry(spaceId, request.parentSpaceId(), existing.getBuildingId());
        BizSpace space = new BizSpace();
        space.setSpaceId(spaceId);
        applySpace(request.parentSpaceId(), request.spaceName(), request.spaceCode(),
                request.spaceType(), request.sortOrder(), request.usableArea(), space);
        call(() -> spaceService.update(space).getData());
        return spaceView(requireSpace(spaceId), Map.of(), new HashSet<>());
    }

    @Transactional
    public void deleteSpace(String spaceId, Collection<String> roles) {
        requireAdmin(roles);
        requireSpace(spaceId);
        long children = spaceService.count(new LambdaQueryWrapper<BizSpace>()
                .eq(BizSpace::getParentSpaceId, spaceId));
        long equipment = equipmentService.count(new LambdaQueryWrapper<BizEquipment>()
                .eq(BizEquipment::getSpaceId, spaceId));
        if (children + equipment > 0) {
            throw AssetErrors.error(409, AssetErrors.REFERENCE_CONFLICT,
                    "空间仍有子空间或设备引用，不能删除");
        }
        call(() -> spaceService.delete(spaceId).getData());
    }

    public PageResponse<SystemGroupView> listSystemGroups(
            int page, int size, String buildingId, String keyword, Collection<String> roles) {
        requireAdmin(roles);
        if (StringUtils.hasText(buildingId)) requireBuilding(buildingId);
        validatePage(page, size);
        IPage<BizSystemGroup> result = systemGroupService.list(
                page, size, buildingId, keyword, null).getData();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::systemGroupView).toList());
    }

    @Transactional
    public SystemGroupView createSystemGroup(
            SystemGroupCreateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        requireBuilding(request.buildingId());
        requireActiveStatus(request.status());
        BizSystemGroup group = new BizSystemGroup();
        group.setBuildingId(request.buildingId());
        group.setSystemGroupCode(request.systemCode());
        group.setSystemGroupName(request.systemName());
        group.setSystemType(request.systemType());
        return systemGroupView(call(() -> systemGroupService.add(group).getData()));
    }

    @Transactional
    public SystemGroupView updateSystemGroup(
            String systemGroupId, SystemGroupUpdateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        requireSystemGroup(systemGroupId);
        requireActiveStatus(request.status());
        BizSystemGroup group = new BizSystemGroup();
        group.setSystemGroupId(systemGroupId);
        group.setSystemGroupName(request.systemName());
        group.setSystemType(request.systemType());
        call(() -> systemGroupService.update(group).getData());
        return systemGroupView(requireSystemGroup(systemGroupId));
    }

    @Transactional
    public void deleteSystemGroup(String systemGroupId, Collection<String> roles) {
        requireAdmin(roles);
        requireSystemGroup(systemGroupId);
        References references = systemGroupReferences(systemGroupId);
        if (references.equipment() + references.points() > 0) {
            throw AssetErrors.error(409, AssetErrors.REFERENCE_CONFLICT,
                    "系统分组仍有设备或测点引用，不能删除");
        }
        call(() -> systemGroupService.delete(systemGroupId).getData());
    }

    public PageResponse<EquipmentListItemView> listEquipment(
            int page, int size, String buildingId, String spaceId, String systemGroupId,
            String typeCode, String productId, String status, String keyword,
            Collection<String> roles) {
        requireAdmin(roles);
        validatePage(page, size);
        LambdaQueryWrapper<BizEquipment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(buildingId), BizEquipment::getBuildingId, buildingId)
                .eq(StringUtils.hasText(spaceId), BizEquipment::getSpaceId, spaceId)
                .eq(StringUtils.hasText(systemGroupId), BizEquipment::getSystemGroupId, systemGroupId)
                .eq(StringUtils.hasText(typeCode), BizEquipment::getTypeCode, typeCode)
                .eq(StringUtils.hasText(productId), BizEquipment::getProductId, productId);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(item -> item.like(BizEquipment::getEquipName, keyword)
                    .or().like(BizEquipment::getEquipCode, keyword));
        }
        wrapper.orderByDesc(BizEquipment::getCreateTime);
        if (!StringUtils.hasText(status)) {
            IPage<BizEquipment> result = equipmentService.page(new Page<>(page, size), wrapper);
            return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                    result.getRecords().stream().map(this::equipmentListView).toList());
        }
        // 设备状态由身份集合派生，当前表无冗余状态列；仅状态筛选时在受控管理查询内聚合后分页。
        List<EquipmentListItemView> filtered = equipmentService.list(wrapper).stream()
                .map(this::equipmentListView)
                .filter(item -> status.equalsIgnoreCase(item.status()))
                .toList();
        return pageValues(filtered, page, size);
    }

    public EquipmentDetailView equipmentDetail(String equipmentId, Collection<String> roles) {
        requireAdmin(roles);
        return equipmentDetailView(requireEquipment(equipmentId));
    }

    @Transactional
    public EquipmentListItemView createEquipment(
            EquipmentCreateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        requireActiveStatus(request.status());
        BizEquipment equipment = new BizEquipment();
        equipment.setBuildingId(request.buildingId());
        equipment.setSpaceId(request.spaceId());
        equipment.setSystemGroupId(request.systemGroupId());
        equipment.setTypeCode(request.typeCode());
        equipment.setEquipName(request.equipmentName());
        equipment.setProductId(request.productId());
        equipment.setManufacturer(request.manufacturer());
        equipment.setRatedCapacity(request.ratedCapacity());
        equipment.setRatedPower(request.ratedPower());
        equipment.setDesignCop(request.designCop());
        return equipmentListView(call(() -> equipmentService.add(equipment).getData()));
    }

    @Transactional
    public EquipmentListItemView updateEquipment(
            String equipmentId, EquipmentUpdateRequest request, Collection<String> roles) {
        requireAdmin(roles);
        BizEquipment existing = requireEquipment(equipmentId);
        requireActiveStatus(request.status());
        if (!existing.getBuildingId().equals(request.buildingId())) {
            throw AssetErrors.error(400, AssetErrors.VALIDATION_FAILED,
                    "设备不能通过普通编辑跨建筑移动");
        }
        BizEquipment equipment = new BizEquipment();
        equipment.setEquipId(equipmentId);
        equipment.setSpaceId(request.spaceId());
        equipment.setSystemGroupId(request.systemGroupId());
        equipment.setEquipName(request.equipmentName());
        equipment.setManufacturer(request.manufacturer());
        equipment.setRatedCapacity(request.ratedCapacity());
        equipment.setRatedPower(request.ratedPower());
        equipment.setDesignCop(request.designCop());
        call(() -> equipmentService.update(equipment).getData());
        return equipmentListView(requireEquipment(equipmentId));
    }

    @Transactional
    public void deleteEquipment(String equipmentId, Collection<String> roles) {
        requireAdmin(roles);
        requireEquipment(equipmentId);
        References references = equipmentReferences(equipmentId);
        if (references.points() + references.identities() > 0) {
            throw AssetErrors.error(409, AssetErrors.REFERENCE_CONFLICT,
                    "设备仍有测点或外部身份引用，不能删除");
        }
        call(() -> equipmentService.delete(equipmentId).getData());
    }

    public List<PointView> listPoints(String equipmentId, Collection<String> roles) {
        requireAdmin(roles);
        BizEquipment equipment = requireEquipment(equipmentId);
        Set<String> requiredSuffixes = requiredSuffixes(equipment.getProductId());
        return dataPointService.listByEquip(equipmentId).getData().stream()
                .map(point -> pointView(point, requiredSuffixes.contains(point.getSuffixCode())))
                .toList();
    }

    @Transactional
    public PointView updatePoint(
            String equipmentId, String pointId, PointUpdateRequest request,
            Collection<String> roles) {
        requireAdmin(roles);
        BizEquipment equipment = requireEquipment(equipmentId);
        BizDataPoint existing = requirePointForEquipment(pointId, equipmentId);
        BizDataPoint point = new BizDataPoint();
        point.setPointId(pointId);
        point.setPointName(request.pointName());
        point.setValueMin(request.minValue());
        point.setValueMax(request.maxValue());
        point.setIsForCalc(request.forCalculation() == null
                ? existing.getIsForCalc() : Boolean.TRUE.equals(request.forCalculation()) ? 1 : 0);
        point.setStatus(StringUtils.hasText(request.status()) ? request.status() : existing.getStatus());
        call(() -> dataPointService.update(point).getData());
        return pointView(requirePointForEquipment(pointId, equipmentId),
                requiredSuffixes(equipment.getProductId()).contains(existing.getSuffixCode()));
    }

    @Transactional
    public void deletePoint(
            String equipmentId, String pointId, Collection<String> roles) {
        requireAdmin(roles);
        requireEquipment(equipmentId);
        requirePointForEquipment(pointId, equipmentId);
        long aliases = aliasMapper.selectCount(new LambdaQueryWrapper<BizPointAlias>()
                .eq(BizPointAlias::getPointId, pointId));
        if (aliases > 0) {
            throw AssetErrors.error(409, AssetErrors.REFERENCE_CONFLICT,
                    "测点仍被来源别名引用，不能删除");
        }
        call(() -> dataPointService.delete(pointId).getData());
    }

    private BuildingView buildingView(Building building) {
        References references = buildingReferences(building.getBuildingId());
        return new BuildingView(building.getBuildingId(), building.getBuildingName(),
                building.getBuildingCode(), building.getBuildingType(), building.getConstructionYear(),
                building.getTotalGfa(), building.getClimateZone(), "ACTIVE", references,
                parentActions(references.spaces() + references.systemGroups() + references.equipment()
                        + references.points() + references.authorizations() == 0),
                millis(building.getUpdateTime(), building.getCreateTime()));
    }

    private References buildingReferences(String buildingId) {
        return references(
                spaceService.count(new LambdaQueryWrapper<BizSpace>().eq(BizSpace::getBuildingId, buildingId)),
                systemGroupService.count(new LambdaQueryWrapper<BizSystemGroup>().eq(BizSystemGroup::getBuildingId, buildingId)),
                equipmentService.count(new LambdaQueryWrapper<BizEquipment>().eq(BizEquipment::getBuildingId, buildingId)),
                dataPointService.count(new LambdaQueryWrapper<BizDataPoint>().eq(BizDataPoint::getBuildingId, buildingId)),
                userBuildingMapper.selectCount(new LambdaQueryWrapper<SysUserBuilding>().eq(SysUserBuilding::getBuildingId, buildingId)),
                0, 0, 0);
    }

    private SpaceView spaceView(
            BizSpace space, Map<String, List<BizSpace>> byParent, Set<String> path) {
        if (!path.add(space.getSpaceId())) {
            throw AssetErrors.error(409, AssetErrors.STATE_CONFLICT, "空间层级存在循环，无法展示");
        }
        List<SpaceView> children = byParent.getOrDefault(space.getSpaceId(), List.of()).stream()
                .sorted(spaceComparator())
                .map(child -> spaceView(child, byParent, new HashSet<>(path)))
                .toList();
        long equipment = equipmentService.count(new LambdaQueryWrapper<BizEquipment>()
                .eq(BizEquipment::getSpaceId, space.getSpaceId()));
        References refs = references(0, 0, equipment, 0, 0, children.size(), 0, 0);
        return new SpaceView(space.getSpaceId(), space.getBuildingId(), space.getParentSpaceId(),
                space.getSpaceName(), space.getSpaceCode(), space.getSpaceType(),
                Objects.requireNonNullElse(space.getFloorLevel(), 0), space.getUsableArea(), "ACTIVE",
                refs, parentActions(children.isEmpty() && equipment == 0), children,
                millis(space.getUpdateTime(), space.getCreateTime()));
    }

    private SystemGroupView systemGroupView(BizSystemGroup group) {
        References refs = systemGroupReferences(group.getSystemGroupId());
        return new SystemGroupView(group.getSystemGroupId(), group.getBuildingId(),
                group.getSystemGroupCode(), group.getSystemGroupName(), group.getSystemType(), 0,
                "ACTIVE", refs, actions(refs.equipment() + refs.points() == 0),
                millis(group.getUpdateTime(), group.getCreateTime()));
    }

    private References systemGroupReferences(String id) {
        return references(0, 0,
                equipmentService.count(new LambdaQueryWrapper<BizEquipment>().eq(BizEquipment::getSystemGroupId, id)),
                dataPointService.count(new LambdaQueryWrapper<BizDataPoint>().eq(BizDataPoint::getSystemGroupId, id)),
                0, 0, 0, 0);
    }

    private EquipmentListItemView equipmentListView(BizEquipment equipment) {
        EquipmentAggregate aggregate = aggregate(equipment);
        return new EquipmentListItemView(equipment.getEquipId(), equipment.getEquipCode(),
                equipment.getEquipName(), equipment.getTypeCode(), equipment.getEquipCategory(),
                equipment.getBuildingId(), aggregate.buildingName(), equipment.getSpaceId(),
                aggregate.spaceName(), equipment.getSystemGroupId(), aggregate.systemGroupName(),
                equipment.getProductId(), aggregate.productName(), aggregate.status(),
                aggregate.expectedProfileCode(), aggregate.lastDiscoveredTime(), aggregate.pointSummary(),
                actions(aggregate.pointSummary().total() == 0 && aggregate.identities().isEmpty()),
                millis(equipment.getUpdateTime(), equipment.getCreateTime()));
    }

    private EquipmentDetailView equipmentDetailView(BizEquipment equipment) {
        EquipmentAggregate aggregate = aggregate(equipment);
        References refs = equipmentReferences(equipment.getEquipId());
        return new EquipmentDetailView(equipment.getEquipId(), equipment.getEquipCode(),
                equipment.getEquipName(), equipment.getTypeCode(), equipment.getEquipCategory(),
                equipment.getBuildingId(), aggregate.buildingName(), equipment.getSpaceId(),
                aggregate.spaceName(), equipment.getSystemGroupId(), aggregate.systemGroupName(),
                equipment.getProductId(), aggregate.productName(), equipment.getManufacturer(),
                equipment.getRatedCapacity(), equipment.getRatedPower(), equipment.getDesignCop(),
                aggregate.status(), aggregate.expectedProfileCode(), aggregate.lastDiscoveredTime(),
                aggregate.identities(), aggregate.pointSummary(), refs,
                actions(refs.points() + refs.identities() == 0),
                millis(equipment.getUpdateTime(), equipment.getCreateTime()));
    }

    private EquipmentAggregate aggregate(BizEquipment equipment) {
        Building building = buildingService.getById(equipment.getBuildingId());
        BizSpace space = spaceService.getById(equipment.getSpaceId());
        BizSystemGroup group = systemGroupService.getById(equipment.getSystemGroupId());
        BizDeviceProduct product = StringUtils.hasText(equipment.getProductId())
                ? productMapper.selectById(equipment.getProductId()) : null;
        List<BizDeviceIdentity> identities = identityMapper.selectList(
                new LambdaQueryWrapper<BizDeviceIdentity>().eq(BizDeviceIdentity::getEquipId, equipment.getEquipId()));
        List<IdentityView> identityViews = identities.stream().map(identity -> new IdentityView(
                identity.getIdentityId(), identity.getIdentityType(), identity.getIdentityValue(),
                identityStatus(identity.getStatus()), identity.getExpectedProfileCode())).toList();
        String status = identities.isEmpty() ? "UNBOUND"
                : identities.stream().anyMatch(item -> Integer.valueOf(1).equals(item.getStatus()))
                ? "ACTIVE"
                : identities.stream().allMatch(item -> Integer.valueOf(0).equals(item.getStatus()))
                ? "DISABLED" : "UNKNOWN";
        String profile = identities.stream().map(BizDeviceIdentity::getExpectedProfileCode)
                .filter(Objects::nonNull).distinct().findFirst().orElse(null);
        Long lastSeen = lastDiscoveredTime(identities);
        List<BizDataPoint> points = dataPointService.list(new LambdaQueryWrapper<BizDataPoint>()
                .eq(BizDataPoint::getEquipId, equipment.getEquipId()));
        Set<String> requiredSuffixes = requiredSuffixes(equipment.getProductId());
        long total = points.size();
        long required = requiredSuffixes.size();
        long configured = points.stream()
                .map(BizDataPoint::getSuffixCode)
                .filter(requiredSuffixes::contains)
                .distinct()
                .count();
        return new EquipmentAggregate(building == null ? null : building.getBuildingName(),
                space == null ? null : space.getSpaceName(), group == null ? null : group.getSystemGroupName(),
                product == null ? null : product.getProductName(), status, profile, lastSeen,
                identityViews, new PointSummary(total, required, configured));
    }

    private Long lastDiscoveredTime(List<BizDeviceIdentity> identities) {
        List<String> ids = identities.stream().map(BizDeviceIdentity::getIdentityId).toList();
        if (ids.isEmpty()) return null;
        return pendingMapper.selectList(new LambdaQueryWrapper<BizPendingDevice>()
                        .in(BizPendingDevice::getBoundIdentityId, ids)).stream()
                .map(BizPendingDevice::getLastSeenTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(AssetManagementService::millis)
                .orElse(null);
    }

    private PointView pointView(BizDataPoint point, boolean required) {
        List<BizPointAlias> aliases = aliasMapper.selectList(new LambdaQueryWrapper<BizPointAlias>()
                .eq(BizPointAlias::getPointId, point.getPointId()));
        List<String> sourceAliases = aliases.stream().map(BizPointAlias::getSourcePointCode).toList();
        References refs = references(0, 0, 0, 0, 0, 0, aliases.size(), 0);
        return new PointView(point.getPointId(), point.getEquipId(), point.getPointCode(),
                point.getPointName(), point.getDataType(), point.getUnit(), point.getValueMin(),
                point.getValueMax(), Integer.valueOf(1).equals(point.getIsForCalc()), required,
                point.getStatus(), sourceAliases, refs, actions(aliases.isEmpty()),
                millis(point.getUpdateTime(), point.getCreateTime()));
    }

    private Set<String> requiredSuffixes(String productId) {
        if (!StringUtils.hasText(productId)) return Set.of();
        return productPointMapper.selectList(new LambdaQueryWrapper<BizProductPointTemplate>()
                        .eq(BizProductPointTemplate::getProductId, productId)
                        .eq(BizProductPointTemplate::getRequiredFlag, 1)
                        .eq(BizProductPointTemplate::getStatus, 1)).stream()
                .map(BizProductPointTemplate::getSuffixCode).collect(java.util.stream.Collectors.toSet());
    }

    private References equipmentReferences(String id) {
        return references(0, 0, 0,
                dataPointService.count(new LambdaQueryWrapper<BizDataPoint>().eq(BizDataPoint::getEquipId, id)),
                0, 0, 0,
                identityMapper.selectCount(new LambdaQueryWrapper<BizDeviceIdentity>().eq(BizDeviceIdentity::getEquipId, id)));
    }

    private void validateSpaceAncestry(String spaceId, String parentId, String buildingId) {
        Set<String> visited = new HashSet<>();
        String cursor = parentId;
        while (StringUtils.hasText(cursor)) {
            if (!visited.add(cursor) || spaceId.equals(cursor)) {
                throw AssetErrors.error(409, AssetErrors.STATE_CONFLICT,
                        "父空间选择会形成循环层级");
            }
            BizSpace parent = spaceService.getById(cursor);
            if (parent == null || !buildingId.equals(parent.getBuildingId())) {
                throw AssetErrors.error(400, AssetErrors.VALIDATION_FAILED,
                        "父空间不存在或不属于同一建筑");
            }
            cursor = parent.getParentSpaceId();
        }
    }

    private static void applyBuilding(
            String name, String code, String type, Integer year, java.math.BigDecimal totalGfa,
            String climateZone, Building building) {
        building.setBuildingName(name);
        building.setBuildingCode(code);
        building.setBuildingType(type);
        building.setConstructionYear(year);
        building.setTotalGfa(totalGfa);
        building.setClimateZone(climateZone);
    }

    private static void applySpace(
            String parentId, String name, String code, String type, Integer sortOrder,
            java.math.BigDecimal usableArea, BizSpace space) {
        space.setParentSpaceId(parentId);
        space.setSpaceName(name);
        space.setSpaceCode(code);
        space.setSpaceType(type);
        space.setFloorLevel(sortOrder);
        space.setUsableArea(usableArea);
    }

    private Building requireBuilding(String id) {
        Building value = buildingService.getById(id);
        if (value == null) throw AssetErrors.error(404, AssetErrors.NOT_FOUND, "建筑不存在");
        return value;
    }

    private BizSpace requireSpace(String id) {
        BizSpace value = spaceService.getById(id);
        if (value == null) throw AssetErrors.error(404, AssetErrors.NOT_FOUND, "空间不存在");
        return value;
    }

    private BizSystemGroup requireSystemGroup(String id) {
        BizSystemGroup value = systemGroupService.getById(id);
        if (value == null) throw AssetErrors.error(404, AssetErrors.NOT_FOUND, "系统分组不存在");
        return value;
    }

    private BizEquipment requireEquipment(String id) {
        BizEquipment value = equipmentService.getById(id);
        if (value == null) throw AssetErrors.error(404, AssetErrors.NOT_FOUND, "设备不存在");
        return value;
    }

    private BizDataPoint requirePointForEquipment(String pointId, String equipmentId) {
        BizDataPoint value = dataPointService.getById(pointId);
        if (value == null || !equipmentId.equals(value.getEquipId())) {
            throw AssetErrors.error(404, AssetErrors.NOT_FOUND, "设备测点不存在");
        }
        return value;
    }

    private static void requireAdmin(Collection<String> roles) {
        if (roles == null || roles.stream().noneMatch("PLATFORM_ADMIN"::equalsIgnoreCase)) {
            throw AssetErrors.error(403, "ASSET_FORBIDDEN", "只有平台管理员可以管理资产档案");
        }
    }

    private static void requireActiveStatus(String status) {
        if (StringUtils.hasText(status) && !"ACTIVE".equalsIgnoreCase(status)) {
            throw AssetErrors.error(400, AssetErrors.VALIDATION_FAILED,
                    "当前档案结构只接受 ACTIVE 状态；停用使用受保护删除操作");
        }
    }

    private static <T> T call(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != null) throw exception;
            String code = exception.getCode() == 404 ? AssetErrors.NOT_FOUND
                    : exception.getCode() == 409 ? AssetErrors.STATE_CONFLICT
                    : AssetErrors.VALIDATION_FAILED;
            throw AssetErrors.error(exception.getCode(), code, exception.getMessage());
        }
    }

    private static String identityStatus(Integer status) {
        if (Integer.valueOf(1).equals(status)) return "ACTIVE";
        if (Integer.valueOf(0).equals(status)) return "DISABLED";
        return "UNKNOWN";
    }

    private static List<String> actions(boolean deletable) {
        return deletable ? EDIT_ACTIONS : List.of("UPDATE");
    }

    private static List<String> parentActions(boolean deletable) {
        return deletable ? PARENT_EDIT_ACTIONS : List.of("CREATE", "UPDATE");
    }

    private static References references(
            long spaces, long systemGroups, long equipment, long points, long authorizations,
            long children, long aliases, long identities) {
        return new References(spaces, systemGroups, equipment, points, authorizations,
                children, aliases, identities);
    }

    private static Comparator<BizSpace> spaceComparator() {
        return Comparator.comparing(BizSpace::getFloorLevel,
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static long millis(Date preferred, Date fallback) {
        Date value = preferred != null ? preferred : fallback;
        return value == null ? 0 : value.getTime();
    }

    private static long millis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static <V> PageResponse<V> pageValues(List<V> values, int page, int size) {
        validatePage(page, size);
        int from = Math.min((page - 1) * size, values.size());
        int to = Math.min(from + size, values.size());
        return new PageResponse<>(page, size, values.size(), values.subList(from, to));
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 200) {
            throw AssetErrors.error(400, AssetErrors.VALIDATION_FAILED,
                    "分页参数超出允许范围");
        }
    }

    private record EquipmentAggregate(
            String buildingName,
            String spaceName,
            String systemGroupName,
            String productName,
            String status,
            String expectedProfileCode,
            Long lastDiscoveredTime,
            List<IdentityView> identities,
            PointSummary pointSummary) {
    }
}
