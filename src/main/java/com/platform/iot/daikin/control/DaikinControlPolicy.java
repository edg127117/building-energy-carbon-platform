package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 协议范围与设备有效范围同时满足才放行；字段缺失/未知不降级为可控制。 */
@Component
public class DaikinControlPolicy {
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final List<String> MODES = List.of("cooling", "heating", "dependent", "fan", "dry",
            "automaticCooling", "automaticHeating", "ventilationMonitorOnly");
    private static final List<String> FANS = List.of("low", "middle", "high", "automatic", "middleLow", "middleHigh");
    private static final List<String> FLOWS = List.of("airFlowZero", "airFlowOne", "airFlowTwo", "airFlowThree", "airFlowFour", "airFlowSeven", "airFlowAuto");
    public static final String TEST_FAST = "TEST_FAST_V1";

    /** 测试只采用协议/厂家能力；不读取逐设备许可、主从人工摘要或风向确认标记。 */
    public List<ActionOption> testOptions(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot snapshot) {
        String blocked = testBlocked(target, snapshot);
        if (blocked != null) return Arrays.stream(Action.values()).map(a -> denied(a, blocked)).toList();
        JsonNode unit = snapshot.unit();
        return List.of(enumeration(Action.ON_OFF, unit.get("onOffModeSetList"), List.of("on", "off")),
                testTemperature(unit), enumeration(Action.MODE, unit.get("modeSetList"), MODES),
                new ActionOption(Action.AIR_FLOW, true, null, FLOWS, null, null),
                enumeration(Action.FAN_SPEED, unit.get("fanSpeedSetList"), FANS));
    }

    public String testRevision(DaikinControlAccess.Target target) {
        // 当前值、许可和关联关系变化不使测试页面失效，正式身份改变仍必须重新取得选项。
        return DaikinControlAccess.hash(DaikinControlAccess.identity(target).fingerprint() + "|" + TEST_FAST);
    }

    public String testSettings(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot snapshot) {
        String blocked = testBlocked(target, snapshot);
        if (blocked != null) throw error(409, blocked);
        var values = json.createObjectNode();
        for (Action action : Action.values()) {
            String value = rawObserved(snapshot.unit(), action);
            if (value != null) values.put(action.name(), value);
        }
        values.put("unitStatus", snapshot.unit().path("unitStatus").asText());
        values.put("_executionPolicy", TEST_FAST);
        return values.toString();
    }

    /** 只核验本次字段；现场关机不使调温失败，也不触发补发开机或恢复其他字段。 */
    public boolean testMatches(String settings, Action action, String requested) {
        try {
            JsonNode current = json.readTree(settings);
            if (!requested.equals(current.path(action.name()).asText())) return false;
            return action != Action.ON_OFF || ("on".equals(requested) ? "operating" : "stopped").equals(current.path("unitStatus").asText());
        } catch (java.io.IOException | IllegalArgumentException ex) { return false; }
    }

    public static boolean isFastBaseline(String baseline) {
        if (baseline == null) return false;
        try { return TEST_FAST.equals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(baseline).path("_executionPolicy").asText()); }
        catch (java.io.IOException ex) { return false; }
    }

    public String testObserved(String settings, Action action) {
        try { return json.readTree(settings).path(action.name()).asText(null); }
        catch (java.io.IOException ex) { return null; }
    }

    private String rawObserved(JsonNode unit, Action action) {
        var value = unit.get(action.observedField);
        if (action == Action.TEMPERATURE) {
            Integer n = integer(value);
            return n != null && n >= 16 && n <= 32 ? n.toString() : null;
        }
        List<String> allowed = switch (action) {
            case ON_OFF -> List.of("on", "off"); case MODE -> MODES; case FAN_SPEED -> FANS; case AIR_FLOW -> FLOWS;
            default -> List.of();
        };
        return value != null && value.isTextual() && allowed.contains(value.textValue()) ? value.textValue() : null;
    }

    private String testBlocked(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot snapshot) {
        if (!identityMatches(target, snapshot)) return "IDENTITY_CONFLICT";
        JsonNode controller = snapshot.controller();
        if (!bool(controller.get("isConnectionUp"), true) || !bool(controller.get("inForcedStop"), false)
                || controller.hasNonNull("decommissioned") && !controller.path("decommissioned").asText().isBlank())
            return "CONTROLLER_UNAVAILABLE";
        return null;
    }

    private ActionOption testTemperature(JsonNode unit) {
        String mode = unit.path("mode").asText();
        if (Set.of("cooling", "automaticCooling", "heating", "automaticHeating").contains(mode)) return temperature(unit);
        JsonNode range = unit.path("DefaultSetpointRange");
        Integer min = integer(range.get("min")), max = integer(range.get("max")), step = integer(range.get("step"));
        if (min == null || max == null || step == null || step != 1 || min > max) return denied(Action.TEMPERATURE, "TEMPERATURE_RANGE_UNKNOWN");
        min = Math.max(16, min); max = Math.min(32, max);
        return min <= max ? new ActionOption(Action.TEMPERATURE, true, null, List.of(), min, max) : denied(Action.TEMPERATURE, "TEMPERATURE_RANGE_UNKNOWN");
    }

    public List<ActionOption> options(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot snapshot) {
        String blocked = blocked(target, snapshot);
        if (blocked != null) return Arrays.stream(Action.values()).map(a -> denied(a, blocked)).toList();
        JsonNode unit = snapshot.unit();
        List<ActionOption> result = new ArrayList<>();
        result.add(enumeration(Action.ON_OFF, unit.get("onOffModeSetList"), List.of("on", "off")));
        result.add(temperature(unit));
        result.add(enumeration(Action.MODE, unit.get("modeSetList"), MODES));
        result.add(target.airFlowConfirmed() ? new ActionOption(Action.AIR_FLOW, true, null, FLOWS, null, null)
                : denied(Action.AIR_FLOW, "AIR_FLOW_UNCONFIRMED"));
        result.add(enumeration(Action.FAN_SPEED, unit.get("fanSpeedSetList"), FANS));
        return result.stream().map(option -> granted(target, option)).toList();
    }

    public String revision(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot snapshot) {
        // 固定顺序排除厂家 JSON 属性顺序差异，同时覆盖提交前可能变化的联锁、能力与当前值。
        StringBuilder text = new StringBuilder(target.fingerprint());
        for (String field : List.of("isConnectionUp", "inForcedStop", "decommissioned"))
            text.append('|').append(snapshot.controller().path(field));
        for (String field : List.of("id", "unitId", "onOff", "mode", "temperature", "fanSpeed", "airflowDirection",
                "unitStatus", "inCommunicationError", "inEquipmentError", "inMaintenanceMode", "inMantenanceMode",
                "isGroupSlave", "masterSlaveFlag", "masterSlaveIds", "modeSetList", "onOffModeSetList", "fanSpeedSetList",
                "DefaultSetpointRange", "limitSettempCool", "limitSettempHeat", "coolLimitsettempU", "coolLimitsettempL",
                "heatLimitsettempU", "heatLimitsettempL")) text.append('|').append(snapshot.unit().path(field));
        return DaikinControlAccess.hash(text.toString());
    }

    public void validate(Action action, String value, List<ActionOption> options) {
        if (action == null || value == null || value.length() > 40) throw error(400, "INVALID_REQUEST");
        ActionOption option = options.stream().filter(o -> o.action() == action).findFirst().orElseThrow();
        if (!option.allowed()) throw error(409, option.reasonCode());
        if (action == Action.TEMPERATURE) {
            if (!value.matches("[0-9]{2}") || Integer.parseInt(value) < option.minimum()
                    || Integer.parseInt(value) > option.maximum()
                    || !option.values().isEmpty() && !option.values().contains(value)) throw error(400, "INVALID_REQUEST");
        } else if (!option.values().contains(value)) throw error(400, "INVALID_REQUEST");
    }

    public String observed(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot snapshot, Action action) {
        // 厂家可能在离线时返回缓存值；失联、故障或关系不明的快照不能作为操作核验依据。
        String unavailable = blocked(target, snapshot);
        if (unavailable != null) throw error(409, unavailable);
        JsonNode value = snapshot.unit().get(action.observedField);
        if (value == null || value.isNull()) return null;
        if (action == Action.TEMPERATURE) {
            Integer n = integer(value);
            return n != null && n >= 16 && n <= 32 ? n.toString() : null;
        }
        List<String> allowed = switch (action) {
            case ON_OFF -> List.of("on", "off");
            case MODE -> MODES;
            case FAN_SPEED -> FANS;
            case AIR_FLOW -> FLOWS;
            default -> List.of();
        };
        return value.isTextual() && allowed.contains(value.textValue()) ? value.textValue() : null;
    }

    private String blocked(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot s) {
        if (!identityMatches(target, s)) return "IDENTITY_CONFLICT";
        JsonNode c = s.controller(), u = s.unit();
        if (!bool(c.get("isConnectionUp"), true) || !bool(c.get("inForcedStop"), false)
                || c.hasNonNull("decommissioned") && !c.path("decommissioned").asText().isBlank()) return "CONTROLLER_UNAVAILABLE";
        JsonNode maintenance = u.has("inMaintenanceMode") ? u.get("inMaintenanceMode") : u.get("inMantenanceMode");
        if (!bool(u.get("inCommunicationError"), false) || !bool(u.get("inEquipmentError"), false)
                || !bool(maintenance, false) || !Set.of("operating", "stopped").contains(u.path("unitStatus").asText()))
            return "DEVICE_UNAVAILABLE";
        if (!bool(u.get("isGroupSlave"), false) || !Set.of("Master", "Slave").contains(u.path("masterSlaveFlag").asText())
                || !u.path("masterSlaveIds").isArray() || target.evidence() == null || target.evidence().isBlank()
                || target.relationshipHash() == null || !target.relationshipHash().matches("[a-f0-9]{64}")
                || !java.util.Objects.equals(target.relationshipHash(), relationshipHash(s))) return "LINKED_DEVICE_UNCONFIRMED";
        return null;
    }

    /** 关系按身份和排序后的关联厂家编号摘要；关联顺序变化不失效，内容或控制器变化必须重新确认。 */
    public String relationshipHash(DaikinControlGateway.Snapshot snapshot) {
        JsonNode u = snapshot.unit(), ids = u.path("masterSlaveIds");
        if (!ids.isArray() || ids.size() > 1000) return null;
        var values = new ArrayList<String>();
        for (JsonNode id : ids) {
            if (!(id.isTextual() || id.isIntegralNumber()) || id.asText().isBlank()) return null;
            values.add(id.asText());
        }
        values.sort(String::compareTo);
        return DaikinControlAccess.hash(json.createArrayNode().add(snapshot.controller().path("lcNo").asText())
                .add(u.path("id").asText()).add(u.path("unitId").asText()).add(u.path("isGroupSlave").toString())
                .add(u.path("masterSlaveFlag").asText()).add(json.valueToTree(values)).toString());
    }

    private ActionOption granted(DaikinControlAccess.Target target, ActionOption option) {
        if (!option.allowed()) return option;
        try {
            if (target.approvedActions() == null || target.approvedActions().length() > 4096) return denied(option.action(), "ACTION_UNCONFIRMED");
            JsonNode permit = json.readTree(target.approvedActions()).path(option.action().name());
            var values = new ArrayList<String>();
            if (permit.isArray() && permit.size() <= 100) {
                for (JsonNode value : permit) { if (!value.isTextual()) return denied(option.action(), "ACTION_UNCONFIRMED"); values.add(value.textValue()); }
            } else if (option.action() == Action.TEMPERATURE && permit.isObject()) {
                Integer min = integer(permit.get("minimum")), max = integer(permit.get("maximum"));
                if (min == null || max == null || min < 16 || max > 32 || min > max) return denied(option.action(), "ACTION_UNCONFIRMED");
                for (int n = min; n <= max; n++) values.add(Integer.toString(n));
            }
            if (option.action() == Action.TEMPERATURE) {
                values.removeIf(value -> !value.matches("[0-9]{2}") || Integer.parseInt(value) < option.minimum() || Integer.parseInt(value) > option.maximum());
                if (values.isEmpty()) return denied(option.action(), "ACTION_UNCONFIRMED");
                int min = values.stream().mapToInt(Integer::parseInt).min().orElseThrow();
                int max = values.stream().mapToInt(Integer::parseInt).max().orElseThrow();
                return new ActionOption(option.action(), true, null, List.copyOf(values), min, max);
            }
            values.retainAll(option.values());
            return values.isEmpty() ? denied(option.action(), "ACTION_UNCONFIRMED")
                    : new ActionOption(option.action(), true, null, values.stream().distinct().toList(), null, null);
        } catch (java.io.IOException e) { return denied(option.action(), "ACTION_UNCONFIRMED"); }
    }

    /** 只保存控制设置和运行状态；室温与响应时钟不作为执行证据。 */
    public String settings(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot snapshot) {
        var settings = json.createObjectNode();
        for (Action action : Action.values()) {
            String value = observed(target, snapshot, action);
            if (value == null) throw error(409, "READBACK_INVALID");
            settings.put(action.name(), value);
        }
        settings.put("unitStatus", snapshot.unit().path("unitStatus").asText());
        return settings.toString();
    }

    public boolean matches(String baseline, String current, Action action, String requested) {
        try {
            JsonNode before = json.readTree(baseline), after = json.readTree(current);
            if (!requested.equals(after.path(action.name()).asText())) return false;
            for (Action other : Action.values())
                if (other != action && !before.path(other.name()).equals(after.path(other.name()))) return false;
            return ("off".equals(after.path("ON_OFF").asText()) && "stopped".equals(after.path("unitStatus").asText()))
                    || ("on".equals(after.path("ON_OFF").asText()) && "operating".equals(after.path("unitStatus").asText()));
        } catch (java.io.IOException | IllegalArgumentException e) { return false; }
    }

    private boolean identityMatches(DaikinControlAccess.Target target, DaikinControlGateway.Snapshot s) {
        return s != null && s.unit() != null && s.controller() != null && s.unit().isObject() && s.controller().isObject()
                && target.vendorId().equals(s.unit().path("id").asText())
                && target.unitId().equals(s.unit().path("unitId").asText())
                && target.controllerId().equals(s.controller().path("lcNo").asText());
    }

    private ActionOption temperature(JsonNode unit) {
        JsonNode range = unit.path("DefaultSetpointRange");
        Integer min = integer(range.get("min")), max = integer(range.get("max")), step = integer(range.get("step"));
        if (min == null || max == null || step == null || step != 1 || min > max) return denied(Action.TEMPERATURE, "TEMPERATURE_RANGE_UNKNOWN");
        String prefix = switch (unit.path("mode").asText()) {
            case "cooling", "automaticCooling" -> "cool";
            case "heating", "automaticHeating" -> "heat";
            default -> null;
        };
        if (prefix == null) return denied(Action.TEMPERATURE, "TEMPERATURE_MODE_UNCONFIRMED");
        String flag = unit.path(prefix.equals("cool") ? "limitSettempCool" : "limitSettempHeat").asText();
        if (!Set.of("on", "off").contains(flag)) return denied(Action.TEMPERATURE, "TEMPERATURE_RANGE_UNKNOWN");
        if ("on".equals(flag)) {
            Integer low = integer(unit.get(prefix + "LimitsettempL")), high = integer(unit.get(prefix + "LimitsettempU"));
            if (low == null || high == null || low < 16 || high > 32 || low > high) return denied(Action.TEMPERATURE, "TEMPERATURE_RANGE_UNKNOWN");
            min = Math.max(min, low); max = Math.min(max, high);
        }
        min = Math.max(16, min); max = Math.min(32, max);
        return min <= max ? new ActionOption(Action.TEMPERATURE, true, null, List.of(), min, max)
                : denied(Action.TEMPERATURE, "TEMPERATURE_RANGE_UNKNOWN");
    }

    private ActionOption enumeration(Action action, JsonNode node, List<String> protocol) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isTextual() && node.textValue().length() <= 1024) {
            String text = node.textValue().trim();
            if (text.startsWith("[") && text.endsWith("]"))
                for (String token : text.substring(1, text.length() - 1).split(",")) values.add(token.trim());
        } else if (node != null && node.isArray() && node.size() <= 100) {
            for (JsonNode item : node) { if (!item.isTextual()) return denied(action, "CAPABILITY_UNKNOWN"); values.add(item.textValue()); }
        }
        if (values.isEmpty() || !protocol.containsAll(values)) return denied(action, "CAPABILITY_UNKNOWN");
        return new ActionOption(action, true, null, values.stream().distinct().toList(), null, null);
    }

    private static boolean bool(JsonNode value, boolean expected) { return value != null && value.isBoolean() && value.booleanValue() == expected; }
    private static Integer integer(JsonNode value) {
        if (value == null || !value.isNumber()) return null;
        try { BigDecimal n = value.decimalValue(); return n.intValueExact(); }
        catch (ArithmeticException e) { return null; }
    }
    private static ActionOption denied(Action action, String reason) { return new ActionOption(action, false, reason, List.of(), null, null); }
}
