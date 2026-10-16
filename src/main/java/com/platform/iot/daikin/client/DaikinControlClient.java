package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.iot.daikin.control.DaikinControlAccess.Target;
import com.platform.iot.daikin.control.DaikinControlContracts.Action;
import com.platform.iot.daikin.control.DaikinControlGateway;
import java.util.Map;
import java.util.List;

/** 源身份固定，单内机响应同时核对项目、控制器和两个厂家编号；不接受任意路径或重试控制。 */
public final class DaikinControlClient implements DaikinControlGateway {
    private static final Map<String, List<String>> SNAPSHOT_ENUMS = Map.of(
            "onOff", List.of("on", "off"),
            "mode", List.of("cooling", "heating", "dependent", "fan", "dry", "automaticCooling", "automaticHeating", "ventilationMonitorOnly"),
            "fanSpeed", List.of("low", "middle", "high", "automatic", "middleLow", "middleHigh"),
            "unitStatus", List.of("operating", "stopped"),
            "masterSlaveFlag", List.of("Master", "Slave"));
    private final String sourceId;
    private final DaikinSession session;
    private final boolean temperatureCelsiusConfirmed;
    public DaikinControlClient(String sourceId, DaikinSession session) { this(sourceId, session, true); }
    public DaikinControlClient(String sourceId, DaikinSession session, boolean temperatureCelsiusConfirmed) {
        this.sourceId = sourceId; this.session = session; this.temperatureCelsiusConfirmed = temperatureCelsiusConfirmed;
    }
    @Override public boolean supports(String sourceId) { return this.sourceId.equals(sourceId); }

    @Override public Snapshot read(Target target) {
        requireSource(target);
        // 单机 GET 与控制 PUT 都按管理点 id 寻址；unitId 只用于响应身份核对。
        JsonNode response = session.read(DaikinEndpoint.INUNIT, target.vendorId(), Map.of());
        JsonNode sites = response.path("data").path("sites");
        if (!sites.isArray() || sites.size() > 100) throw invalid();
        Snapshot found = null;
        int count = 0;
        for (JsonNode site : sites) {
            JsonNode controllers = site.path("controlers");
            if (!controllers.isArray() || controllers.size() > 100) throw invalid();
            for (JsonNode controller : controllers) {
                JsonNode units = controller.path("units");
                if (!units.isArray() || units.size() > 100) throw invalid();
                for (JsonNode unit : units) {
                    if (++count > 100) throw invalid();
                    if (target.unitId().equals(unit.path("unitId").asText())) {
                        if (found != null || !target.siteId().equals(site.path("siteId").asText())
                                || !target.controllerId().equals(controller.path("lcNo").asText())
                                || !target.vendorId().equals(unit.path("id").asText())) throw invalid();
                        // 单位撤回确认时，同时禁止温度操作和温度核验，不能只关闭监测解码。
                        if (!(unit instanceof ObjectNode)) throw invalid();
                        ObjectNode safeUnit = unit.deepCopy();
                        // 厂家状态枚举存在大小写差异；只规范已知成员，未知值仍由策略拒绝。
                        SNAPSHOT_ENUMS.forEach((field, values) -> {
                            JsonNode value = safeUnit.get(field);
                            if (value != null && value.isTextual())
                                values.stream().filter(known -> known.equalsIgnoreCase(value.textValue())).findFirst()
                                        .ifPresent(known -> safeUnit.put(field, known));
                        });
                        if (!temperatureCelsiusConfirmed)
                            safeUnit.remove(java.util.List.of("DefaultSetpointRange", "temperature"));
                        found = new Snapshot(controller, safeUnit);
                    }
                }
            }
        }
        if (found == null) throw invalid();
        return found;
    }

    @Override public List<SendPermit> prepareSendWave(int size) { return session.prepareSendWave(size); }

    @Override public Reply send(Target target, Action action, String value) { return send(target,action,value,null); }

    @Override public Reply send(Target target, Action action, String value,SendPermit permit) {
        requireSource(target);
        if (action == Action.TEMPERATURE && !temperatureCelsiusConfirmed) throw invalid();
        DaikinEndpoint endpoint = switch (action) {
            case ON_OFF -> DaikinEndpoint.CONTROL_ON_OFF;
            case TEMPERATURE -> DaikinEndpoint.CONTROL_TEMPERATURE;
            case MODE -> DaikinEndpoint.CONTROL_MODE;
            case AIR_FLOW -> DaikinEndpoint.CONTROL_AIR_FLOW;
            case FAN_SPEED -> DaikinEndpoint.CONTROL_FAN_SPEED;
        };
        JsonNode envelope = permit==null ? session.controlOnce(endpoint,target.vendorId(),Map.of(action.parameter,value))
                : session.controlOnce(endpoint,target.vendorId(),Map.of(action.parameter,value),permit);
        JsonNode code = envelope == null ? null : envelope.get("code");
        if (code == null || !code.isTextual() || !code.textValue().matches("[0-9]{5,6}")) throw invalid();
        return new Reply(code.textValue());
    }
    private void requireSource(Target target) { if (!supports(target.sourceId())) throw invalid(); }
    private static DaikinClientException invalid() { return new DaikinClientException(DaikinClientException.Code.INVALID_RESPONSE); }
}
