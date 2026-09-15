package com.platform.audit.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.platform.audit.system.SystemSensitiveCommandSupport.*;

/** 类型化绑定使用公共审批状态机；与旧数值命令分开校验，不能用空测点绕过旧约束。 */
@Component
@RequiredArgsConstructor
public class BindTypedPendingDeviceHandler implements SensitiveOperationHandler {
    public static final String CODE = "BIND_TYPED_PENDING_DEVICE";
    private final SystemSensitiveCommandSupport support;
    private final DeviceOnboardingService service;

    @Override public String operationCode() { return CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        String message = "类型化设备绑定命令无效";
        Command value = support.read(command, Command.class, message);
        String pendingId = requireText(value.pendingId(), 32, message);
        if (value.binding() == null) throw invalid(message);
        var binding = value.binding();
        String equipmentId = optionalText(binding.existingEquipmentId(), 32, message);
        var equipment = binding.newEquipment();
        if ((equipmentId == null) == (equipment == null)) throw invalid(message);
        var normalized = new DeviceOnboardingContracts.TypedBindRequest(
                requireText(binding.productId(), 32, message), requireText(binding.buildingId(), 32, message),
                requireText(binding.spaceId(), 32, message), requireText(binding.systemGroupId(), 32, message),
                equipmentId, equipment == null ? null : new DeviceOnboardingContracts.NewEquipmentRequest(
                        requireText(equipment.equipmentName(), 100, message),
                        optionalText(equipment.manufacturer(), 100, message)));
        String building = service.resolveTypedBindBuilding(pendingId, normalized,
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return new NormalizedSensitiveCommand(building, "PENDING_DEVICE", pendingId,
                support.canonical(new Command(pendingId, normalized), message),
                "buildingId=" + building + ";bindingType=TYPED_STATE;pointCount=0");
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "类型化设备绑定命令无效");
        service.bindTyped(value.pendingId(), value.binding(), context.reviewerId(),
                DeviceOnboardingSensitiveOperationHandlers.PLATFORM_ADMIN);
        return SensitiveOperationResult.none();
    }

    public record Command(String pendingId, DeviceOnboardingContracts.TypedBindRequest binding) { }
}
