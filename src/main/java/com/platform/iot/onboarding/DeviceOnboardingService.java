package com.platform.iot.onboarding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizDeviceIdentityMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizEquipmentTypeMapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.mapper.BizPointNamingRuleMapper;
import com.platform.hvac.mapper.BizSpaceMapper;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizDeviceIdentity;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizEquipmentType;
import com.platform.hvac.model.entity.BizPointAlias;
import com.platform.hvac.model.entity.BizPointNamingRule;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.service.EquipmentCodeAllocator;
import com.platform.hvac.service.PointCodeNamingValidator;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.identity.MySqlDeviceIdentityProvider;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts;
import com.platform.iot.onboarding.mapper.BizDeviceProductMapper;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.mapper.BizProductPointTemplateMapper;
import com.platform.iot.onboarding.model.entity.BizDeviceProduct;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import com.platform.iot.onboarding.model.entity.BizProductPointTemplate;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.platform.iot.onboarding.OnboardingErrors.*;

@Service
@RequiredArgsConstructor
/** 待绑定状态机、绑定事务和身份缓存生效的后端权威边界。 */
public class DeviceOnboardingService {
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final int EQUIPMENT_CODE_ATTEMPTS = 3;

    private final BizPendingDeviceMapper pendingMapper;
    private final BizDeviceProductMapper productMapper;
    private final BizProductPointTemplateMapper templateMapper;
    private final BuildingMapper buildingMapper;
    private final BizSpaceMapper spaceMapper;
    private final BizSystemGroupMapper groupMapper;
    private final BizEquipmentMapper equipmentMapper;
    private final BizEquipmentTypeMapper equipmentTypeMapper;
    private final BizDeviceIdentityMapper identityMapper;
    private final BizDataPointMapper pointMapper;
    private final BizPointAliasMapper aliasMapper;
    private final BizPointNamingRuleMapper namingRuleMapper;
    private final EquipmentCodeAllocator equipmentCodeAllocator;
    private final PointCodeNamingValidator namingValidator;
    private final MySqlDeviceIdentityProvider identityProvider;
    private final MySqlDataPointConfigProvider pointProvider;
    private final OnboardingAuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${ingestion.standard-source-system:MQTT_STANDARD_V1}")
    private String standardSourceSystem;

    public PageResponse<DeviceOnboardingContracts.PendingListItemView> listPending(
            int page,
            int size,
            String status,
            String identity,
            String profileCode,
            Set<String> roles) {
        requireAdmin(roles);
        validatePage(page, size);
        LambdaQueryWrapper<BizPendingDevice> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(BizPendingDevice::getStatus, normalize(status));
        }
        if (StringUtils.hasText(identity)) {
            query.like(BizPendingDevice::getIdentityValue, identity.trim());
        }
        if (StringUtils.hasText(profileCode)) {
            query.eq(BizPendingDevice::getProfileCode, normalize(profileCode));
        }
        query.orderByDesc(BizPendingDevice::getLastSeenTime)
                .orderByAsc(BizPendingDevice::getPendingId);
        Page<BizPendingDevice> result = pendingMapper.selectPage(new Page<>(page, size), query);
        List<DeviceOnboardingContracts.PendingListItemView> items = result.getRecords().stream()
                .map(this::toListItem)
                .toList();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), items);
    }

    public DeviceOnboardingContracts.PendingDetailView pendingDetail(String pendingId, Set<String> roles) {
        requireAdmin(roles);
        return toDetail(requirePending(pendingId));
    }

    @Transactional
    public DeviceOnboardingContracts.PendingDetailView updatePendingStatus(
            String pendingId,
            DeviceOnboardingContracts.PendingStatusRequest request,
            Long operatorId,
            Set<String> roles) {
        requireAdmin(roles);
        String next = normalize(request.status());
        if (!Set.of("DISCOVERED", "IGNORED").contains(next)) {
            throw error(400, VALIDATION_FAILED, "待绑定状态只能是 DISCOVERED 或 IGNORED");
        }
        BizPendingDevice pending = pendingMapper.selectByIdForUpdate(pendingId);
        if (pending == null) {
            throw error(404, NOT_FOUND, "待绑定设备不存在");
        }
        if (next.equals(pending.getStatus())) {
            return toDetail(pending);
        }
        if ("BOUND".equals(pending.getStatus())) {
            throw error(409, STATE_CONFLICT, "已绑定设备不能改变待处理状态");
        }
        String before = pending.getStatus();
        if (pendingMapper.updateStatus(pendingId, before, next, null) != 1) {
            throw error(409, STATE_CONFLICT, "待绑定状态已被其他操作修改");
        }
        pending.setStatus(next);
        auditService.record(operatorId,
                "IGNORED".equals(next) ? "PENDING_IGNORE" : "PENDING_RESTORE",
                "PENDING_DEVICE", pendingId,
                Map.of("status", before),
                statusSummary(next, request.reason()));
        return toDetail(pending);
    }

    /**
     * 在独立 MySQL 事务内完成全套绑定，提交后再刷新并核验两份运行时快照。
     * 缓存未生效不伪装成数据库回滚，响应通过 configEffective 明确标识待生效。
     */
    public DeviceOnboardingContracts.BindResultView bind(
            String pendingId,
            DeviceOnboardingContracts.BindRequest request,
            Long operatorId,
            Set<String> roles) {
        requireAdmin(roles);
        BindTransactionResult result;
        try {
            result = transactionTemplate.execute(status -> doBind(pendingId, request, operatorId));
        } catch (DuplicateKeyException exception) {
            throw error(409, DUPLICATE, "身份、测点或别名已被其他绑定占用");
        }
        if (result == null) {
            throw error(500, CONFIG_PENDING, "绑定事务未返回结果");
        }
        boolean effective = refreshAndVerifyBinding(result);
        return new DeviceOnboardingContracts.BindResultView(
                pendingId, result.identityId(), result.equipmentId(), result.pointIds(), "BOUND", effective);
    }

    /** 身份状态先提交数据库，再以刷新后快照是否可见决定接口能否返回成功。 */
    public DeviceOnboardingContracts.IdentityStatusView activate(
            String identityId, Long operatorId, Set<String> roles) {
        requireAdmin(roles);
        IdentityChange result = transactionTemplate.execute(
                status -> changeIdentityStatus(identityId, 1, operatorId));
        if (result == null) {
            throw error(500, CONFIG_PENDING, "身份启用事务未返回结果");
        }
        identityProvider.refreshAll();
        pointProvider.refreshAll();
        boolean visible;
        try {
            visible = identityProvider.find(result.key()).isPresent()
                    && aliasesVisible(result);
        } catch (RuntimeException exception) {
            visible = false;
        }
        if (!visible) {
            throw error(503, CONFIG_PENDING, "身份已提交启用，但运行时配置尚未生效");
        }
        return new DeviceOnboardingContracts.IdentityStatusView(identityId, "ACTIVE", true);
    }

    public DeviceOnboardingContracts.IdentityStatusView deactivate(
            String identityId, Long operatorId, Set<String> roles) {
        requireAdmin(roles);
        IdentityChange result = transactionTemplate.execute(
                status -> changeIdentityStatus(identityId, 0, operatorId));
        if (result == null) {
            throw error(500, CONFIG_PENDING, "身份停用事务未返回结果");
        }
        identityProvider.refreshAll();
        boolean effective;
        try {
            effective = identityProvider.isKnown(result.key())
                    && identityProvider.find(result.key()).isEmpty();
        } catch (RuntimeException exception) {
            effective = false;
        }
        if (!effective) {
            throw error(503, CONFIG_PENDING, "身份已提交停用，但运行时配置尚未生效");
        }
        return new DeviceOnboardingContracts.IdentityStatusView(identityId, "DISABLED", true);
    }

    private BindTransactionResult doBind(
            String pendingId, DeviceOnboardingContracts.BindRequest request, Long operatorId) {
        BizPendingDevice pending = pendingMapper.selectByIdForUpdate(pendingId);
        if (pending == null) {
            throw error(404, NOT_FOUND, "待绑定设备不存在");
        }
        if (!"DISCOVERED".equals(pending.getStatus())) {
            throw error(409, STATE_CONFLICT, "只有待处理设备可以绑定");
        }
        BizDeviceProduct product = productMapper.selectById(request.productId());
        if (product == null || !"ENABLED".equals(product.getStatus())) {
            throw error(409, VALIDATION_FAILED, "产品不存在或未启用");
        }
        if (!product.getIdentityType().equalsIgnoreCase(pending.getIdentityType())
                || !product.getExpectedProfileCode().equalsIgnoreCase(pending.getProfileCode())) {
            throw error(409, VALIDATION_FAILED, "产品身份类型或协议与待绑定设备不匹配");
        }
        validateOwnership(request);
        BizEquipment equipment = resolveEquipment(request, product);
        BizDeviceIdentity identity = createDisabledIdentity(pending, product, equipment);
        List<BizProductPointTemplate> templates = enabledTemplates(product.getProductId());
        PointBindingResult pointResult = bindPoints(pending, equipment, templates, request.pointBindings());
        if (pendingMapper.updateStatus(pendingId, "DISCOVERED", "BOUND", identity.getIdentityId()) != 1) {
            throw error(409, STATE_CONFLICT, "待绑定状态已被其他操作修改");
        }
        auditService.record(operatorId, "PENDING_BIND", "PENDING_DEVICE", pendingId,
                Map.of("status", "DISCOVERED"),
                Map.of(
                        "status", "BOUND",
                        "productId", product.getProductId(),
                        "buildingId", equipment.getBuildingId(),
                        "equipmentId", equipment.getEquipId(),
                        "identityId", identity.getIdentityId(),
                        "pointCount", pointResult.pointIds().size(),
                        "aliasCount", pointResult.aliases().size()));
        if (request.newEquipment() != null) {
            auditService.record(operatorId, "EQUIPMENT_CREATE", "EQUIPMENT", equipment.getEquipId(),
                    null, Map.of(
                            "buildingId", equipment.getBuildingId(),
                            "systemGroupId", equipment.getSystemGroupId(),
                            "spaceId", equipment.getSpaceId(),
                            "productId", product.getProductId()));
        }
        auditService.record(operatorId, "IDENTITY_CREATE", "DEVICE_IDENTITY", identity.getIdentityId(),
                null, Map.of(
                        "buildingId", identity.getBuildingId(),
                        "equipmentId", identity.getEquipId(),
                        "profileCode", identity.getExpectedProfileCode(),
                        "status", 0));
        for (BoundAlias alias : pointResult.aliases()) {
            auditService.record(operatorId,
                    alias.created() ? "POINT_ALIAS_CREATE" : "POINT_ALIAS_REUSE",
                    "POINT_ALIAS", alias.aliasId(), null,
                    Map.of(
                            "buildingId", alias.key().buildingId(),
                            "metricCode", alias.metricCode(),
                            "pointId", alias.pointId()));
        }
        return new BindTransactionResult(
                identity.getIdentityId(), equipment.getEquipId(), pointResult.pointIds(),
                new DeviceIdentityKey(identity.getIdentityType(), identity.getIdentityValue()),
                pointResult.aliases());
    }

    private void validateOwnership(DeviceOnboardingContracts.BindRequest request) {
        if (buildingMapper.selectById(request.buildingId()) == null) {
            throw error(404, NOT_FOUND, "目标建筑不存在");
        }
        BizSpace space = spaceMapper.selectById(request.spaceId());
        if (space == null || !request.buildingId().equals(space.getBuildingId())) {
            throw error(400, VALIDATION_FAILED, "空间不属于目标建筑");
        }
        BizSystemGroup group = groupMapper.selectById(request.systemGroupId());
        if (group == null || !request.buildingId().equals(group.getBuildingId())) {
            throw error(400, VALIDATION_FAILED, "系统分组不属于目标建筑");
        }
        boolean existing = StringUtils.hasText(request.existingEquipmentId());
        boolean creating = request.newEquipment() != null;
        if (existing == creating) {
            throw error(400, VALIDATION_FAILED, "必须且只能选择已有设备或新建设备之一");
        }
    }

    private BizEquipment resolveEquipment(
            DeviceOnboardingContracts.BindRequest request, BizDeviceProduct product) {
        if (StringUtils.hasText(request.existingEquipmentId())) {
            BizEquipment equipment = equipmentMapper.selectById(request.existingEquipmentId());
            if (equipment == null) {
                throw error(404, NOT_FOUND, "目标设备不存在");
            }
            if (!request.buildingId().equals(equipment.getBuildingId())
                    || !request.spaceId().equals(equipment.getSpaceId())
                    || !request.systemGroupId().equals(equipment.getSystemGroupId())) {
                throw error(400, VALIDATION_FAILED, "设备、空间、系统分组与建筑归属不一致");
            }
            if (!product.getEquipmentTypeCode().equals(equipment.getTypeCode())) {
                throw error(400, VALIDATION_FAILED, "设备类型与产品不一致");
            }
            if (equipment.getProductId() != null
                    && !product.getProductId().equals(equipment.getProductId())) {
                throw error(409, STATE_CONFLICT, "已有设备已关联其他产品版本");
            }
            return equipment;
        }
        BizEquipmentType type = equipmentTypeMapper.selectById(product.getEquipmentTypeCode());
        if (type == null || !Integer.valueOf(1).equals(type.getStatus())) {
            throw error(409, VALIDATION_FAILED, "产品关联的设备类型不可用");
        }
        BizEquipment equipment = new BizEquipment();
        equipment.setEquipName(request.newEquipment().equipmentName().trim());
        equipment.setTypeCode(type.getTypeCode());
        equipment.setEquipCategory(type.getEquipCategory());
        equipment.setSystemGroupId(request.systemGroupId());
        equipment.setBuildingId(request.buildingId());
        equipment.setSpaceId(request.spaceId());
        equipment.setProductId(product.getProductId());
        equipment.setManufacturer(StringUtils.hasText(request.newEquipment().manufacturer())
                ? request.newEquipment().manufacturer().trim() : product.getManufacturer());
        equipment.setCreateTime(new Date());
        equipment.setUpdateTime(equipment.getCreateTime());
        equipment.setDelFlag(0);
        for (int attempt = 1; attempt <= EQUIPMENT_CODE_ATTEMPTS; attempt++) {
            equipment.setEquipId(null);
            equipment.setEquipCode(equipmentCodeAllocator.next(type.getAssetCodePrefix(),
                    equipmentMapper.selectHistoricalCodes(request.buildingId(), type.getTypeCode())));
            try {
                equipmentMapper.insert(equipment);
                return equipment;
            } catch (DuplicateKeyException exception) {
                if (attempt == EQUIPMENT_CODE_ATTEMPTS) {
                    throw error(409, DUPLICATE, "设备编码并发冲突，请稍后重试");
                }
            }
        }
        throw error(409, DUPLICATE, "设备编码分配失败");
    }

    private BizDeviceIdentity createDisabledIdentity(
            BizPendingDevice pending, BizDeviceProduct product, BizEquipment equipment) {
        Long existing = identityMapper.selectCount(new LambdaQueryWrapper<BizDeviceIdentity>()
                .eq(BizDeviceIdentity::getIdentityType, pending.getIdentityType())
                .eq(BizDeviceIdentity::getIdentityValue, pending.getIdentityValue()));
        if (existing > 0) {
            throw error(409, DUPLICATE, "设备身份已被登记");
        }
        BizDeviceIdentity identity = new BizDeviceIdentity();
        identity.setIdentityType(pending.getIdentityType());
        identity.setIdentityValue(pending.getIdentityValue());
        identity.setEquipId(equipment.getEquipId());
        // 身份建筑从已校验设备派生，客户端 buildingId 不能直接覆盖可信绑定。
        identity.setBuildingId(equipment.getBuildingId());
        identity.setExpectedProfileCode(product.getExpectedProfileCode());
        identity.setStatus(0);
        identity.setCreateTime(new Date());
        identity.setUpdateTime(identity.getCreateTime());
        identityMapper.insert(identity);
        return identity;
    }

    private PointBindingResult bindPoints(
            BizPendingDevice pending,
            BizEquipment equipment,
            List<BizProductPointTemplate> templates,
            List<DeviceOnboardingContracts.PointBindingRequest> requests) {
        Map<String, BizProductPointTemplate> templateByMetric = new LinkedHashMap<>();
        templates.forEach(template -> templateByMetric.put(template.getMetricCode(), template));
        Map<String, DeviceOnboardingContracts.PointBindingRequest> requestByMetric = new LinkedHashMap<>();
        for (DeviceOnboardingContracts.PointBindingRequest request : requests) {
            if (requestByMetric.putIfAbsent(request.metricCode().trim(), request) != null) {
                throw error(400, VALIDATION_FAILED, "绑定请求包含重复指标代码");
            }
            if (!templateByMetric.containsKey(request.metricCode().trim())) {
                throw error(400, VALIDATION_FAILED, "绑定请求包含产品未启用的指标");
            }
        }
        for (BizProductPointTemplate template : templates) {
            if (Integer.valueOf(1).equals(template.getRequiredFlag())
                    && !requestByMetric.containsKey(template.getMetricCode())) {
                throw error(400, VALIDATION_FAILED, "缺少产品必填指标映射: " + template.getMetricCode());
            }
        }
        List<String> pointIds = new ArrayList<>();
        List<BoundAlias> aliases = new ArrayList<>();
        for (Map.Entry<String, DeviceOnboardingContracts.PointBindingRequest> entry
                : requestByMetric.entrySet()) {
            BizProductPointTemplate template = templateByMetric.get(entry.getKey());
            BizDataPoint point = resolvePoint(equipment, template, entry.getValue());
            String sourcePointCode = "%s:%s:%s".formatted(
                    pending.getIdentityType(), pending.getIdentityValue(), template.getMetricCode());
            BizPointAlias alias = existingAlias(equipment.getBuildingId(), sourcePointCode);
            boolean aliasCreated = false;
            if (alias == null) {
                alias = new BizPointAlias();
                alias.setBuildingId(equipment.getBuildingId());
                alias.setSourceSystem(standardSourceSystem);
                alias.setSourcePointCode(sourcePointCode);
                alias.setPointId(point.getPointId());
                alias.setStatus(1);
                aliasMapper.insert(alias);
                aliasCreated = true;
            } else if (!point.getPointId().equals(alias.getPointId())) {
                throw error(409, DUPLICATE, "来源别名已指向其他测点");
            } else if (!Integer.valueOf(1).equals(alias.getStatus())) {
                throw error(409, STATE_CONFLICT, "来源别名未启用，不能复用");
            }
            pointIds.add(point.getPointId());
            aliases.add(new BoundAlias(
                    alias.getAliasId(),
                    new PointAliasKey(equipment.getBuildingId(), standardSourceSystem, sourcePointCode),
                    point.getPointId(), template.getMetricCode(), aliasCreated));
        }
        return new PointBindingResult(List.copyOf(pointIds), List.copyOf(aliases));
    }

    private BizDataPoint resolvePoint(
            BizEquipment equipment,
            BizProductPointTemplate template,
            DeviceOnboardingContracts.PointBindingRequest request) {
        if (StringUtils.hasText(request.existingPointId())) {
            if (hasNewPointFields(request)) {
                throw error(400, VALIDATION_FAILED, "已有测点映射不能同时提交新测点字段");
            }
            BizDataPoint point = pointMapper.selectById(request.existingPointId());
            validateExistingPoint(equipment, template, point);
            return point;
        }
        requireNewPointFields(request);
        BizPointNamingRule rule = namingRuleMapper.selectById(request.namingRuleId());
        if (rule == null || !Integer.valueOf(1).equals(rule.getStatus())
                || !rule.getFamilyCode().equals(request.familyCode())
                || !rule.getComponentCode().equals(request.componentCode())
                || !namingValidator.matches(rule, request.pointCode())) {
            throw error(400, VALIDATION_FAILED, "新测点编码或命名规则不合法");
        }
        if (!request.pointCode().endsWith("_" + template.getSuffixCode())) {
            throw error(400, VALIDATION_FAILED, "新测点后缀与产品模板不一致");
        }
        if (pointMapper.selectCount(new LambdaQueryWrapper<BizDataPoint>()
                .eq(BizDataPoint::getBuildingId, equipment.getBuildingId())
                .eq(BizDataPoint::getPointCode, request.pointCode())) > 0) {
            throw error(409, DUPLICATE, "建筑内测点编码已存在，请显式选择已有测点");
        }
        BizDataPoint point = new BizDataPoint();
        point.setPointCode(request.pointCode().trim());
        point.setPointName(StringUtils.hasText(request.pointName())
                ? request.pointName().trim() : template.getPointNameTemplate());
        point.setBuildingId(equipment.getBuildingId());
        point.setEquipId(equipment.getEquipId());
        point.setSystemGroupId(equipment.getSystemGroupId());
        point.setNamingRuleId(rule.getRuleId());
        point.setFamilyCode(request.familyCode());
        point.setComponentCode(request.componentCode());
        point.setSuffixCode(template.getSuffixCode());
        point.setDataType(normalize(request.dataType()));
        point.setUnit(template.getUnit());
        point.setIsForCalc(template.getForCalc());
        point.setValueMin(template.getMinValue());
        point.setValueMax(template.getMaxValue());
        point.setStatus("ONLINE");
        point.setCreateTime(new Date());
        point.setUpdateTime(point.getCreateTime());
        point.setDelFlag(0);
        pointMapper.insert(point);
        return point;
    }

    private void validateExistingPoint(
            BizEquipment equipment, BizProductPointTemplate template, BizDataPoint point) {
        if (point == null) {
            throw error(404, NOT_FOUND, "已有测点不存在");
        }
        if (!equipment.getBuildingId().equals(point.getBuildingId())
                || !equipment.getEquipId().equals(point.getEquipId())
                || !equipment.getSystemGroupId().equals(point.getSystemGroupId())) {
            throw error(400, VALIDATION_FAILED, "已有测点与目标设备归属不一致");
        }
        if (!Objects.equals(template.getUnit(), point.getUnit())
                || !Objects.equals(template.getSuffixCode(), point.getSuffixCode())) {
            throw error(400, VALIDATION_FAILED, "已有测点单位或语义与产品模板不一致");
        }
        if (!"ONLINE".equalsIgnoreCase(point.getStatus())) {
            throw error(409, VALIDATION_FAILED, "已有测点未启用");
        }
    }

    private BizPointAlias existingAlias(String buildingId, String sourcePointCode) {
        return aliasMapper.selectOne(new LambdaQueryWrapper<BizPointAlias>()
                .eq(BizPointAlias::getBuildingId, buildingId)
                .eq(BizPointAlias::getSourceSystem, standardSourceSystem)
                .eq(BizPointAlias::getSourcePointCode, sourcePointCode));
    }

    private IdentityChange changeIdentityStatus(String identityId, int next, Long operatorId) {
        BizDeviceIdentity identity = identityMapper.selectByIdForUpdate(identityId);
        if (identity == null) {
            throw error(404, NOT_FOUND, "设备身份不存在");
        }
        if (identity.getStatus() != null && identity.getStatus() == next) {
            return new IdentityChange(
                    new DeviceIdentityKey(identity.getIdentityType(), identity.getIdentityValue()),
                    identity.getBuildingId());
        }
        if (next == 1 && identityAliases(identity).isEmpty()) {
            throw error(409, VALIDATION_FAILED, "身份没有可用的设备专属测点别名");
        }
        int before = Integer.valueOf(1).equals(identity.getStatus()) ? 1 : 0;
        identity.setStatus(next);
        identity.setUpdateTime(new Date());
        identityMapper.updateById(identity);
        auditService.record(operatorId, next == 1 ? "IDENTITY_ACTIVATE" : "IDENTITY_DEACTIVATE",
                "DEVICE_IDENTITY", identityId,
                Map.of("status", before), Map.of("status", next, "buildingId", identity.getBuildingId()));
        return new IdentityChange(
                new DeviceIdentityKey(identity.getIdentityType(), identity.getIdentityValue()),
                identity.getBuildingId());
    }

    private boolean aliasesVisible(IdentityChange identity) {
        List<BizPointAlias> aliases = identityAliases(identity.key(), identity.buildingId());
        if (aliases.isEmpty()) {
            return false;
        }
        return aliases.stream().allMatch(alias -> pointProvider.find(new PointAliasKey(
                        alias.getBuildingId(), alias.getSourceSystem(), alias.getSourcePointCode()))
                .map(config -> alias.getPointId().equals(config.pointId()))
                .orElse(false));
    }

    private List<BizPointAlias> identityAliases(BizDeviceIdentity identity) {
        return identityAliases(
                new DeviceIdentityKey(identity.getIdentityType(), identity.getIdentityValue()),
                identity.getBuildingId());
    }

    private List<BizPointAlias> identityAliases(DeviceIdentityKey key, String buildingId) {
        String prefix = "%s:%s:".formatted(key.type(), key.value());
        return aliasMapper.selectList(new LambdaQueryWrapper<BizPointAlias>()
                        .eq(BizPointAlias::getBuildingId, buildingId)
                        .eq(BizPointAlias::getSourceSystem, standardSourceSystem)
                        .eq(BizPointAlias::getStatus, 1)).stream()
                .filter(alias -> alias.getSourcePointCode().startsWith(prefix))
                .toList();
    }

    private boolean refreshAndVerifyBinding(BindTransactionResult result) {
        identityProvider.refreshAll();
        pointProvider.refreshAll();
        try {
            if (!identityProvider.isKnown(result.identityKey())
                    || identityProvider.find(result.identityKey()).isPresent()) {
                return false;
            }
            for (BoundAlias alias : result.aliases()) {
                if (pointProvider.find(alias.key())
                        .map(config -> alias.pointId().equals(config.pointId()))
                        .orElse(false) == false) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<BizProductPointTemplate> enabledTemplates(String productId) {
        List<BizProductPointTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<BizProductPointTemplate>()
                        .eq(BizProductPointTemplate::getProductId, productId)
                        .eq(BizProductPointTemplate::getStatus, 1)
                        .orderByAsc(BizProductPointTemplate::getSortOrder)
                        .orderByAsc(BizProductPointTemplate::getTemplatePointId));
        if (templates.isEmpty()) {
            throw error(409, VALIDATION_FAILED, "产品没有启用测点模板");
        }
        return templates;
    }

    private DeviceOnboardingContracts.PendingListItemView toListItem(BizPendingDevice pending) {
        return new DeviceOnboardingContracts.PendingListItemView(
                pending.getPendingId(), pending.getIdentityType(), mask(pending.getIdentityValue()),
                pending.getProfileCode(), pending.getLastProfileVersion(), pending.getStatus(),
                pending.getReportCount(), epoch(pending.getFirstSeenTime()), epoch(pending.getLastSeenTime()),
                Integer.valueOf(1).equals(pending.getSampleTruncated()));
    }

    private DeviceOnboardingContracts.PendingDetailView toDetail(BizPendingDevice pending) {
        return new DeviceOnboardingContracts.PendingDetailView(
                pending.getPendingId(), pending.getIdentityType(), pending.getIdentityValue(),
                pending.getProfileCode(), pending.getLastProfileVersion(), pending.getStatus(),
                pending.getBoundIdentityId(), pending.getReportCount(), epoch(pending.getFirstSeenTime()),
                epoch(pending.getLastSeenTime()), epoch(pending.getLatestEventTime()),
                pending.getLatestTimeSource(), parseMetrics(pending.getLatestMetricsJson()),
                Integer.valueOf(1).equals(pending.getSampleTruncated()), allowedActions(pending.getStatus()));
    }

    private JsonNode parseMetrics(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw error(409, VALIDATION_FAILED, "待绑定指标样例已损坏");
        }
    }

    private BizPendingDevice requirePending(String pendingId) {
        BizPendingDevice pending = pendingMapper.selectById(pendingId);
        if (pending == null) {
            throw error(404, NOT_FOUND, "待绑定设备不存在");
        }
        return pending;
    }

    private void requireAdmin(Set<String> roles) {
        if (roles == null || roles.stream().map(DeviceOnboardingService::normalize).noneMatch(ADMIN::contains)) {
            throw error(403, FORBIDDEN, "只有平台管理员可以管理设备接入");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw error(400, VALIDATION_FAILED, "分页参数超出允许范围");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static long epoch(LocalDateTime value) {
        return value == null ? 0 : value.atZone(MYSQL_ZONE).toInstant().toEpochMilli();
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        int visible = Math.min(3, value.length() / 3);
        return value.substring(0, visible) + "****" + value.substring(value.length() - visible);
    }

    private static List<String> allowedActions(String status) {
        return switch (status) {
            case "DISCOVERED" -> List.of("IGNORE", "BIND");
            case "IGNORED" -> List.of("RESTORE");
            default -> List.of();
        };
    }

    private static Map<String, ?> statusSummary(String status, String reason) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("status", status);
        if (StringUtils.hasText(reason)) {
            summary.put("reason", reason.trim());
        }
        return Map.copyOf(summary);
    }

    private static boolean hasNewPointFields(DeviceOnboardingContracts.PointBindingRequest request) {
        return StringUtils.hasText(request.pointCode()) || StringUtils.hasText(request.namingRuleId())
                || StringUtils.hasText(request.familyCode()) || StringUtils.hasText(request.componentCode())
                || StringUtils.hasText(request.dataType()) || StringUtils.hasText(request.pointName());
    }

    private static void requireNewPointFields(DeviceOnboardingContracts.PointBindingRequest request) {
        if (!StringUtils.hasText(request.pointCode()) || !StringUtils.hasText(request.namingRuleId())
                || !StringUtils.hasText(request.familyCode()) || !StringUtils.hasText(request.componentCode())
                || !StringUtils.hasText(request.dataType())) {
            throw error(400, VALIDATION_FAILED, "新测点必须明确编码、命名规则、族、部件和数据类型");
        }
    }

    private record BoundAlias(
            String aliasId,
            PointAliasKey key,
            String pointId,
            String metricCode,
            boolean created) {
    }

    private record PointBindingResult(List<String> pointIds, List<BoundAlias> aliases) {
    }

    private record BindTransactionResult(
            String identityId,
            String equipmentId,
            List<String> pointIds,
            DeviceIdentityKey identityKey,
            List<BoundAlias> aliases) {
    }

    private record IdentityChange(DeviceIdentityKey key, String buildingId) {
    }
}
