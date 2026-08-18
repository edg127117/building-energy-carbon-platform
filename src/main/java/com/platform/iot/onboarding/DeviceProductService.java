package com.platform.iot.onboarding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizEquipmentTypeMapper;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizEquipmentType;
import com.platform.iot.onboarding.api.DeviceProductContracts;
import com.platform.iot.onboarding.mapper.BizDeviceProductMapper;
import com.platform.iot.onboarding.mapper.BizProductPointTemplateMapper;
import com.platform.iot.onboarding.model.entity.BizDeviceProduct;
import com.platform.iot.onboarding.model.entity.BizProductPointTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.platform.iot.onboarding.OnboardingErrors.*;

@Service
@RequiredArgsConstructor
/** 产品型号与测点模板的事务、状态和不可变版本边界。 */
public class DeviceProductService {
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    private final BizDeviceProductMapper productMapper;
    private final BizProductPointTemplateMapper templateMapper;
    private final BizEquipmentTypeMapper equipmentTypeMapper;
    private final BizEquipmentMapper equipmentMapper;
    private final OnboardingAuditService auditService;

    public PageResponse<DeviceProductContracts.ListItemView> list(
            int page, int size, String status, String keyword, Set<String> roles) {
        requireAdmin(roles);
        validatePage(page, size);
        LambdaQueryWrapper<BizDeviceProduct> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            query.eq(BizDeviceProduct::getStatus, normalize(status));
        }
        if (StringUtils.hasText(keyword)) {
            query.and(item -> item.like(BizDeviceProduct::getProductCode, keyword.trim())
                    .or().like(BizDeviceProduct::getProductName, keyword.trim())
                    .or().like(BizDeviceProduct::getModel, keyword.trim()));
        }
        query.orderByDesc(BizDeviceProduct::getUpdateTime)
                .orderByAsc(BizDeviceProduct::getProductId);
        Page<BizDeviceProduct> result = productMapper.selectPage(new Page<>(page, size), query);
        List<DeviceProductContracts.ListItemView> items = result.getRecords().stream()
                .map(product -> new DeviceProductContracts.ListItemView(
                        product.getProductId(), product.getProductCode(), product.getProductName(),
                        product.getManufacturer(), product.getModel(), product.getEquipmentTypeCode(),
                        product.getExpectedProfileCode(), product.getIdentityType(), product.getStatus(),
                        Math.toIntExact(templateMapper.selectCount(new LambdaQueryWrapper<BizProductPointTemplate>()
                                .eq(BizProductPointTemplate::getProductId, product.getProductId()))),
                        epoch(product.getUpdateTime())))
                .toList();
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), items);
    }

    public DeviceProductContracts.DetailView detail(String productId, Set<String> roles) {
        requireAdmin(roles);
        return toDetail(requireProduct(productId));
    }

    @Transactional
    public DeviceProductContracts.DetailView create(
            DeviceProductContracts.CreateRequest request, Long operatorId, Set<String> roles) {
        requireAdmin(roles);
        validateEquipmentType(request.equipmentTypeCode());
        validateTemplates(request.points());
        BizDeviceProduct product = new BizDeviceProduct();
        product.setProductCode(normalize(request.productCode()));
        product.setProductName(request.productName().trim());
        product.setManufacturer(trimToNull(request.manufacturer()));
        product.setModel(trimToNull(request.model()));
        product.setEquipmentTypeCode(normalize(request.equipmentTypeCode()));
        product.setExpectedProfileCode(normalize(request.expectedProfileCode()));
        product.setIdentityType(normalize(request.identityType()));
        product.setStatus("DRAFT");
        product.setCreateTime(LocalDateTime.now());
        product.setUpdateTime(product.getCreateTime());
        try {
            productMapper.insert(product);
            replaceTemplates(product.getProductId(), request.points());
        } catch (DuplicateKeyException exception) {
            throw error(409, DUPLICATE, "产品编码或指标代码已存在");
        }
        auditService.record(operatorId, "PRODUCT_CREATE", "DEVICE_PRODUCT", product.getProductId(),
                null, summary(product));
        return toDetail(product);
    }

    @Transactional
    public DeviceProductContracts.DetailView update(
            String productId,
            DeviceProductContracts.UpdateRequest request,
            Long operatorId,
            Set<String> roles) {
        requireAdmin(roles);
        BizDeviceProduct product = requireProductForUpdate(productId);
        requireDraftAndUnused(product);
        validateEquipmentType(request.equipmentTypeCode());
        validateTemplates(request.points());
        Map<String, ?> before = summary(product);
        product.setProductName(request.productName().trim());
        product.setManufacturer(trimToNull(request.manufacturer()));
        product.setModel(trimToNull(request.model()));
        product.setEquipmentTypeCode(normalize(request.equipmentTypeCode()));
        product.setExpectedProfileCode(normalize(request.expectedProfileCode()));
        product.setIdentityType(normalize(request.identityType()));
        product.setUpdateTime(LocalDateTime.now());
        try {
            productMapper.updateById(product);
            replaceTemplates(productId, request.points());
        } catch (DuplicateKeyException exception) {
            throw error(409, DUPLICATE, "产品指标代码重复");
        }
        auditService.record(operatorId, "PRODUCT_UPDATE", "DEVICE_PRODUCT", productId,
                before, summary(product));
        return toDetail(product);
    }

    @Transactional
    public DeviceProductContracts.DetailView copy(
            String sourceId,
            DeviceProductContracts.CopyRequest request,
            Long operatorId,
            Set<String> roles) {
        requireAdmin(roles);
        BizDeviceProduct source = requireProductForUpdate(sourceId);
        List<BizProductPointTemplate> sourcePoints = templates(sourceId);
        BizDeviceProduct copy = new BizDeviceProduct();
        copy.setProductCode(normalize(request.productCode()));
        copy.setProductName(request.productName().trim());
        copy.setManufacturer(source.getManufacturer());
        copy.setModel(source.getModel());
        copy.setEquipmentTypeCode(source.getEquipmentTypeCode());
        copy.setExpectedProfileCode(source.getExpectedProfileCode());
        copy.setIdentityType(source.getIdentityType());
        copy.setStatus("DRAFT");
        copy.setCreateTime(LocalDateTime.now());
        copy.setUpdateTime(copy.getCreateTime());
        try {
            productMapper.insert(copy);
            for (BizProductPointTemplate point : sourcePoints) {
                point.setTemplatePointId(null);
                point.setProductId(copy.getProductId());
                point.setCreateTime(copy.getCreateTime());
                point.setUpdateTime(copy.getCreateTime());
                templateMapper.insert(point);
            }
        } catch (DuplicateKeyException exception) {
            throw error(409, DUPLICATE, "新产品编码已存在");
        }
        auditService.record(operatorId, "PRODUCT_COPY", "DEVICE_PRODUCT", copy.getProductId(),
                Map.of("sourceProductId", sourceId), summary(copy));
        return toDetail(copy);
    }

    @Transactional
    public DeviceProductContracts.DetailView enable(String productId, Long operatorId, Set<String> roles) {
        requireAdmin(roles);
        BizDeviceProduct product = requireProductForUpdate(productId);
        if ("ENABLED".equals(product.getStatus())) {
            return toDetail(product);
        }
        if (!Set.of("DRAFT", "DISABLED").contains(product.getStatus())) {
            throw error(409, STATE_CONFLICT, "当前产品状态不能启用");
        }
        List<BizProductPointTemplate> points = templates(productId);
        validateStoredTemplates(points);
        String before = product.getStatus();
        product.setStatus("ENABLED");
        product.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(product);
        auditService.record(operatorId, "PRODUCT_ENABLE", "DEVICE_PRODUCT", productId,
                Map.of("status", before), Map.of("status", product.getStatus()));
        return toDetail(product);
    }

    @Transactional
    public DeviceProductContracts.DetailView disable(String productId, Long operatorId, Set<String> roles) {
        requireAdmin(roles);
        BizDeviceProduct product = requireProductForUpdate(productId);
        if ("DISABLED".equals(product.getStatus())) {
            return toDetail(product);
        }
        if (!"ENABLED".equals(product.getStatus())) {
            throw error(409, STATE_CONFLICT, "只有启用产品可以停用");
        }
        product.setStatus("DISABLED");
        product.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(product);
        auditService.record(operatorId, "PRODUCT_DISABLE", "DEVICE_PRODUCT", productId,
                Map.of("status", "ENABLED"), Map.of("status", "DISABLED"));
        return toDetail(product);
    }

    private void replaceTemplates(
            String productId, List<DeviceProductContracts.PointTemplateRequest> requests) {
        templateMapper.delete(new LambdaQueryWrapper<BizProductPointTemplate>()
                .eq(BizProductPointTemplate::getProductId, productId));
        LocalDateTime now = LocalDateTime.now();
        for (DeviceProductContracts.PointTemplateRequest request : requests) {
            BizProductPointTemplate point = new BizProductPointTemplate();
            point.setProductId(productId);
            point.setMetricCode(request.metricCode().trim());
            point.setPointNameTemplate(request.pointNameTemplate().trim());
            point.setSuffixCode(request.suffixCode().trim());
            point.setUnit(request.unit().trim());
            point.setMinValue(request.minValue());
            point.setMaxValue(request.maxValue());
            point.setForCalc(request.forCalc() ? 1 : 0);
            point.setRequiredFlag(request.required() ? 1 : 0);
            point.setSortOrder(request.sortOrder());
            point.setStatus(request.enabled() ? 1 : 0);
            point.setCreateTime(now);
            point.setUpdateTime(now);
            templateMapper.insert(point);
        }
    }

    private void validateTemplates(List<DeviceProductContracts.PointTemplateRequest> points) {
        Set<String> metrics = new HashSet<>();
        for (DeviceProductContracts.PointTemplateRequest point : points) {
            String metric = point.metricCode().trim();
            if (!metrics.add(metric)) {
                throw error(400, VALIDATION_FAILED, "同一产品内指标代码不能重复");
            }
            if (point.minValue() != null && point.maxValue() != null
                    && point.minValue().compareTo(point.maxValue()) > 0) {
                throw error(400, VALIDATION_FAILED, "测点模板下限不能大于上限");
            }
        }
    }

    private void validateStoredTemplates(List<BizProductPointTemplate> points) {
        if (points.isEmpty() || points.stream().noneMatch(point -> Integer.valueOf(1).equals(point.getStatus()))) {
            throw error(409, VALIDATION_FAILED, "产品至少需要一个启用测点模板");
        }
        if (points.stream().anyMatch(point -> point.getUnit() == null || point.getUnit().isBlank())) {
            throw error(409, VALIDATION_FAILED, "产品测点模板单位不完整");
        }
    }

    private void validateEquipmentType(String typeCode) {
        BizEquipmentType type = equipmentTypeMapper.selectById(normalize(typeCode));
        if (type == null || !Integer.valueOf(1).equals(type.getStatus())) {
            throw error(400, VALIDATION_FAILED, "设备类型不存在或已停用");
        }
    }

    private void requireDraftAndUnused(BizDeviceProduct product) {
        if (!"DRAFT".equals(product.getStatus())) {
            throw error(409, STATE_CONFLICT, "只有草稿产品可以原地修改");
        }
        Long count = equipmentMapper.selectCount(new LambdaQueryWrapper<BizEquipment>()
                .eq(BizEquipment::getProductId, product.getProductId()));
        if (count > 0) {
            throw error(409, STATE_CONFLICT, "已被设备使用的产品必须复制为新版本");
        }
    }

    private DeviceProductContracts.DetailView toDetail(BizDeviceProduct product) {
        List<DeviceProductContracts.PointTemplateView> points = templates(product.getProductId()).stream()
                .map(point -> new DeviceProductContracts.PointTemplateView(
                        point.getTemplatePointId(), point.getMetricCode(), point.getPointNameTemplate(),
                        point.getSuffixCode(), point.getUnit(), point.getMinValue(), point.getMaxValue(),
                        Integer.valueOf(1).equals(point.getForCalc()),
                        Integer.valueOf(1).equals(point.getRequiredFlag()),
                        point.getSortOrder(), Integer.valueOf(1).equals(point.getStatus())))
                .toList();
        List<String> allowed = new ArrayList<>();
        allowed.add("COPY");
        if ("DRAFT".equals(product.getStatus())) {
            allowed.add("UPDATE");
            allowed.add("ENABLE");
        } else if ("ENABLED".equals(product.getStatus())) {
            allowed.add("DISABLE");
        } else if ("DISABLED".equals(product.getStatus())) {
            allowed.add("ENABLE");
        }
        return new DeviceProductContracts.DetailView(
                product.getProductId(), product.getProductCode(), product.getProductName(),
                product.getManufacturer(), product.getModel(), product.getEquipmentTypeCode(),
                product.getExpectedProfileCode(), product.getIdentityType(), product.getStatus(),
                points, List.copyOf(allowed), epoch(product.getCreateTime()), epoch(product.getUpdateTime()));
    }

    private List<BizProductPointTemplate> templates(String productId) {
        return templateMapper.selectList(new LambdaQueryWrapper<BizProductPointTemplate>()
                .eq(BizProductPointTemplate::getProductId, productId)
                .orderByAsc(BizProductPointTemplate::getSortOrder)
                .orderByAsc(BizProductPointTemplate::getTemplatePointId));
    }

    private BizDeviceProduct requireProduct(String productId) {
        BizDeviceProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw error(404, NOT_FOUND, "产品不存在");
        }
        return product;
    }

    private BizDeviceProduct requireProductForUpdate(String productId) {
        BizDeviceProduct product = productMapper.selectByIdForUpdate(productId);
        if (product == null) {
            throw error(404, NOT_FOUND, "产品不存在");
        }
        return product;
    }

    private void requireAdmin(Set<String> roles) {
        if (roles == null || roles.stream().map(DeviceProductService::normalize).noneMatch(ADMIN::contains)) {
            throw error(403, FORBIDDEN, "只有平台管理员可以管理设备产品");
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

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static long epoch(LocalDateTime value) {
        return value == null ? 0 : value.atZone(MYSQL_ZONE).toInstant().toEpochMilli();
    }

    private static Map<String, ?> summary(BizDeviceProduct product) {
        return Map.of(
                "productCode", product.getProductCode(),
                "status", product.getStatus(),
                "equipmentTypeCode", product.getEquipmentTypeCode(),
                "profileCode", product.getExpectedProfileCode(),
                "identityType", product.getIdentityType());
    }
}
