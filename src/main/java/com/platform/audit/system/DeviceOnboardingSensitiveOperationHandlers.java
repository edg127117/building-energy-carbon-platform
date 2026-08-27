package com.platform.audit.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.DeviceProductService;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 设备接入敏感命令只负责公共审批适配，产品、绑定和身份状态机仍由领域服务执行。 */
final class DeviceOnboardingSensitiveOperationHandlers {
    static final Set<String> PLATFORM_ADMIN = Set.of("PLATFORM_ADMIN");

    private DeviceOnboardingSensitiveOperationHandlers() {
    }
}

@Component
@RequiredArgsConstructor
/** 执行已批准的设备产品启用，产品模板完整性仍由设备接入领域校验。 */
class EnableDeviceProductHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "ENABLE_DEVICE_PRODUCT";
    private final SystemSensitiveCommandSupport support;
    private final DeviceProductService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        ProductCommand normalized = DeviceOnboardingCommandNormalization.product(
                support, command, "产品启用命令无效");
        service.detail(normalized.productId(), DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return DeviceOnboardingCommandNormalization.productCommand(
                support, normalized, "ENABLE", "产品启用命令无效");
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        ProductCommand value = support.readCanonical(
                command.canonicalJson(), ProductCommand.class, "产品启用命令无效");
        service.enable(value.productId(), context.reviewerId(),
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return SensitiveOperationResult.none();
    }
}

@Component
@RequiredArgsConstructor
/** 执行已批准的设备产品停用，公共层不复制产品状态转换规则。 */
class DisableDeviceProductHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "DISABLE_DEVICE_PRODUCT";
    private final SystemSensitiveCommandSupport support;
    private final DeviceProductService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        ProductCommand normalized = DeviceOnboardingCommandNormalization.product(
                support, command, "产品停用命令无效");
        service.detail(normalized.productId(), DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return DeviceOnboardingCommandNormalization.productCommand(
                support, normalized, "DISABLE", "产品停用命令无效");
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        ProductCommand value = support.readCanonical(
                command.canonicalJson(), ProductCommand.class, "产品停用命令无效");
        service.disable(value.productId(), context.reviewerId(),
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return SensitiveOperationResult.none();
    }
}

@Component
@RequiredArgsConstructor
/** 执行已批准的正式绑定；命令中的建筑必须与领域服务解析出的真实归属一致。 */
class BindPendingDeviceHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "BIND_PENDING_DEVICE";
    private final SystemSensitiveCommandSupport support;
    private final DeviceOnboardingService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        BindCommand normalized = DeviceOnboardingCommandNormalization.bind(support, command);
        String buildingId = service.resolveBindBuilding(
                normalized.pendingId(), normalized.request(),
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return new NormalizedSensitiveCommand(buildingId, "PENDING_DEVICE", normalized.pendingId(),
                support.canonical(normalized, "设备绑定命令无效"),
                "buildingId=" + buildingId + ";pointCount=" + normalized.pointBindings().size());
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        BindCommand value = support.readCanonical(
                command.canonicalJson(), BindCommand.class, "设备绑定命令无效");
        service.bind(value.pendingId(), value.request(), context.reviewerId(),
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return SensitiveOperationResult.none();
    }
}

@Component
@RequiredArgsConstructor
/** 执行已批准的设备身份启用，建筑范围从持久化身份解析。 */
class ActivateDeviceIdentityHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "ACTIVATE_DEVICE_IDENTITY";
    private final SystemSensitiveCommandSupport support;
    private final DeviceOnboardingService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        return identityCommand(support, service, command, "ACTIVATE", "身份启用命令无效");
    }

    @Override public SensitiveOperationResult execute(
            NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        IdentityCommand value = support.readCanonical(
                command.canonicalJson(), IdentityCommand.class, "身份启用命令无效");
        service.activate(value.identityId(), context.reviewerId(),
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return SensitiveOperationResult.none();
    }

    static NormalizedSensitiveCommand identityCommand(
            SystemSensitiveCommandSupport support,
            DeviceOnboardingService service,
            JsonNode command,
            String action,
            String message) {
        IdentityCommand value = support.read(command, IdentityCommand.class, message);
        String identityId = SystemSensitiveCommandSupport.requireText(value.identityId(), 64, message);
        IdentityCommand normalized = new IdentityCommand(identityId);
        String buildingId = service.resolveIdentityBuilding(
                identityId, DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return new NormalizedSensitiveCommand(buildingId, "DEVICE_IDENTITY", identityId,
                support.canonical(normalized, message),
                "buildingId=" + buildingId + ";action=" + action);
    }
}

@Component
@RequiredArgsConstructor
/** 执行已批准的设备身份停用，运行时缓存刷新仍由设备接入领域负责。 */
class DeactivateDeviceIdentityHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "DEACTIVATE_DEVICE_IDENTITY";
    private final SystemSensitiveCommandSupport support;
    private final DeviceOnboardingService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        return ActivateDeviceIdentityHandler.identityCommand(
                support, service, command, "DEACTIVATE", "身份停用命令无效");
    }

    @Override public SensitiveOperationResult execute(
            NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        IdentityCommand value = support.readCanonical(
                command.canonicalJson(), IdentityCommand.class, "身份停用命令无效");
        service.deactivate(value.identityId(), context.reviewerId(),
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return SensitiveOperationResult.none();
    }
}

record ProductCommand(String productId) {
}

record IdentityCommand(String identityId) {
}

record BindCommand(
        String pendingId,
        String productId,
        String buildingId,
        String spaceId,
        String systemGroupId,
        String existingEquipmentId,
        DeviceOnboardingContracts.NewEquipmentRequest newEquipment,
        List<DeviceOnboardingContracts.PointBindingRequest> pointBindings) {

    DeviceOnboardingContracts.BindRequest request() {
        return new DeviceOnboardingContracts.BindRequest(productId, buildingId, spaceId, systemGroupId,
                existingEquipmentId, newEquipment, pointBindings);
    }
}

final class DeviceOnboardingCommandNormalization {
    private static final int MAX_POINT_BINDINGS = 500;

    private DeviceOnboardingCommandNormalization() {
    }

    static ProductCommand product(SystemSensitiveCommandSupport support, JsonNode command, String message) {
        ProductCommand value = support.read(command, ProductCommand.class, message);
        return new ProductCommand(SystemSensitiveCommandSupport.requireText(value.productId(), 64, message));
    }

    static NormalizedSensitiveCommand productCommand(
            SystemSensitiveCommandSupport support, ProductCommand value, String action, String message) {
        return new NormalizedSensitiveCommand(null, "DEVICE_PRODUCT", value.productId(),
                support.canonical(value, message),
                "productId=" + value.productId() + ";action=" + action);
    }

    static BindCommand bind(SystemSensitiveCommandSupport support, JsonNode command) {
        String message = "设备绑定命令无效";
        BindCommand value = support.read(command, BindCommand.class, message);
        String pendingId = SystemSensitiveCommandSupport.requireText(value.pendingId(), 64, message);
        String productId = SystemSensitiveCommandSupport.requireText(value.productId(), 64, message);
        String buildingId = SystemSensitiveCommandSupport.requireText(value.buildingId(), 32, message);
        String spaceId = SystemSensitiveCommandSupport.requireText(value.spaceId(), 64, message);
        String systemGroupId = SystemSensitiveCommandSupport.requireText(value.systemGroupId(), 64, message);
        String equipmentId = SystemSensitiveCommandSupport.optionalText(value.existingEquipmentId(), 64, message);
        DeviceOnboardingContracts.NewEquipmentRequest equipment = normalizeEquipment(value.newEquipment(), message);
        if ((equipmentId == null) == (equipment == null)) {
            throw SystemSensitiveCommandSupport.invalid(message);
        }
        if (value.pointBindings() == null || value.pointBindings().isEmpty()
                || value.pointBindings().size() > MAX_POINT_BINDINGS) {
            throw SystemSensitiveCommandSupport.invalid(message);
        }
        List<DeviceOnboardingContracts.PointBindingRequest> points = new ArrayList<>();
        for (DeviceOnboardingContracts.PointBindingRequest point : value.pointBindings()) {
            if (point == null) throw SystemSensitiveCommandSupport.invalid(message);
            points.add(new DeviceOnboardingContracts.PointBindingRequest(
                    SystemSensitiveCommandSupport.requireText(point.metricCode(), 100, message),
                    SystemSensitiveCommandSupport.optionalText(point.existingPointId(), 64, message),
                    SystemSensitiveCommandSupport.optionalText(point.pointCode(), 100, message),
                    SystemSensitiveCommandSupport.optionalText(point.pointName(), 100, message),
                    SystemSensitiveCommandSupport.optionalText(point.namingRuleId(), 32, message),
                    SystemSensitiveCommandSupport.optionalText(point.familyCode(), 20, message),
                    SystemSensitiveCommandSupport.optionalText(point.componentCode(), 20, message),
                    SystemSensitiveCommandSupport.optionalText(point.dataType(), 20, message)));
        }
        return new BindCommand(pendingId, productId, buildingId, spaceId, systemGroupId,
                equipmentId, equipment, List.copyOf(points));
    }

    private static DeviceOnboardingContracts.NewEquipmentRequest normalizeEquipment(
            DeviceOnboardingContracts.NewEquipmentRequest value, String message) {
        if (value == null) return null;
        return new DeviceOnboardingContracts.NewEquipmentRequest(
                SystemSensitiveCommandSupport.requireText(value.equipmentName(), 100, message),
                SystemSensitiveCommandSupport.optionalText(value.manufacturer(), 100, message));
    }
}
