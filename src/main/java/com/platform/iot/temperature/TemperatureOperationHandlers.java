package com.platform.iot.temperature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.sensitive.*;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.hvac.model.entity.BizEquipment;
import java.util.Set;
import static com.platform.iot.temperature.TemperatureContracts.*;

/** 将温度规则和存量补齐接到公共审批；不另建审核状态机，不改变设备启停。 */
final class TemperatureOperationHandlers {
    private TemperatureOperationHandlers() { }
    static <T> T read(ObjectMapper json, JsonNode command, Class<T> type) {
        try { return json.treeToValue(command, type); }
        catch (Exception ex) { throw TemperaturePlanService.invalid("温度审批命令格式无效"); }
    }
    static JsonNode read(ObjectMapper json, String command) {
        try { return json.readTree(command); }
        catch (Exception ex) { throw TemperaturePlanService.invalid("温度审批命令损坏"); }
    }
}

/** 规则发布执行时再次核对提交人的当前管理员身份，避免通过通用申请入口绕过规则配置权限。 */
@Component
@RequiredArgsConstructor
class ConfigureTemperatureRuleHandler implements SensitiveOperationHandler {
    private final TemperaturePlanService plans;
    private final ObjectMapper json;
    private final SysRoleMapper roles;
    @Override public String operationCode() { return "CONFIGURE_TEMPERATURE_RULE"; }
    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        Rule value = plans.normalizeRule(TemperatureOperationHandlers.read(json, command, Rule.class));
        return new NormalizedSensitiveCommand(value.buildingId(), "TEMPERATURE_RULE", value.ruleId(),
                plans.write(value), "buildingId=" + value.buildingId() + ";adapter=" + value.adapterId() + ";action=CONFIGURE");
    }
    @Override public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        if (!roles.selectRoleKeysByUserId(context.submitterId()).contains("PLATFORM_ADMIN")) {
            throw TemperaturePlanService.invalid("规则提交人已不具备平台管理员权限");
        }
        plans.saveRule(TemperatureOperationHandlers.read(json, TemperatureOperationHandlers.read(json, command.canonicalJson()), Rule.class), context.requestId());
        return SensitiveOperationResult.none();
    }
}

/** 存量补齐按来源映射、待接入身份、设备顺序锁定；任何冲突使该设备整笔事务回滚。 */
@Component
@RequiredArgsConstructor
class BindHvacTemperatureHandler implements SensitiveOperationHandler {
    private final TemperaturePlanService plans;
    private final ObjectMapper json;
    private final BizPendingDeviceMapper pending;
    private final BizEquipmentMapper equipment;
    private final DaikinDirectoryService directory;
    private final BuildingScopeService scope;
    private final SysRoleMapper roles;
    @Override public String operationCode() { return "BIND_HVAC_TEMPERATURE"; }
    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        Item item = TemperatureOperationHandlers.read(json, command, Item.class);
        var plan = plans.validate(item.input(), item.digest(), false);
        if (plan.equipment() == null || !"BOUND".equals(plan.pending().getStatus())) throw TemperaturePlanService.invalid("仅支持已绑定设备补齐");
        return new NormalizedSensitiveCommand(plan.context().buildingId(), "HVAC_TEMPERATURE", item.pendingId(),
                plans.write(item), "buildingId=" + plan.context().buildingId() + ";pointCount=" + plan.points().size());
    }
    @Override public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Item item = TemperatureOperationHandlers.read(json, TemperatureOperationHandlers.read(json, command.canonicalJson()), Item.class);
        var current = plans.requirePending(item.pendingId());
        if ("DAIKIN_UNIT".equals(current.getIdentityType())) directory.requireBinding(item.pendingId(), command.buildingId(), current.getProfileCode());
        pending.selectByIdForUpdate(item.pendingId());
        var plan = plans.validate(item.input(), item.digest(), false);
        scope.checkAccess(context.submitterId(), Set.copyOf(roles.selectRoleKeysByUserId(context.submitterId())), plan.context().buildingId());
        var target = equipment.selectOne(new LambdaQueryWrapper<BizEquipment>()
                .eq(BizEquipment::getEquipId, plan.equipment().getEquipId()).last("FOR UPDATE"));
        plan = plans.validate(item.input(), item.digest(), false);
        plans.install(plan, target, plan.pending().getBoundIdentityId(), context.requestId());
        return SensitiveOperationResult.none();
    }
}
