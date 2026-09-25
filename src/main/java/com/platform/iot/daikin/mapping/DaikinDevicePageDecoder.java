package com.platform.iot.daikin.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.model.DaikinFieldValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.platform.iot.daikin.model.DaikinFieldValue.Status.*;

/**
 * 将厂家内/外机分页响应转换成类型化观测，尚不写入台账或时序库。
 * 结构、分页和身份错误拒绝整页，避免缺失设备被当作成功全量同步；可选字段错误仅隔离该字段。
 */
public final class DaikinDevicePageDecoder {
    private static final Map<String, Set<String>> ENUMS = Map.of(
            "onOff", Set.of("on", "off"),
            "mode", Set.of("cooling", "heating", "dependent", "fan", "dry", "automaticCooling",
                    "automaticHeating", "ventilationMonitorOnly"),
            "fanSpeed", Set.of("low", "middle", "high", "automatic", "middleLow", "middleHigh"),
            "airflowDirection", Set.of("airFlowZero", "airFlowOne", "airFlowTwo", "airFlowThree",
                    "airFlowFour", "airFlowSeven", "airFlowAuto"),
            "unitStatus", Set.of("operating", "stopped", "equipmentErrorOperating",
                    "equipmentErrorStopped", "communicationError", "maintenanceMode", "forcedStop", "unknown"),
            "masterSlaveFlag", Set.of("Master", "Slave"));
    private static final Map<String, Set<String>> PROTOCOL_ENUMS = Map.of(
            "rcProhibitOnOff", Set.of("off", "stopOnly", "on"),
            "rcProhibitOpMode", Set.of("off", "on"),
            "rcProhibitSetpoint", Set.of("off", "on"),
            "limitSettempHeat", Set.of("off", "on"),
            "limitSettempCool", Set.of("off", "on"));
    private static final List<String> SETPOINT_LIMIT_FIELDS = List.of("coolLimitsettempU",
            "coolLimitsettempL", "heatLimitsettempU", "heatLimitsettempL");
    private static final List<String> BOOLEAN_FIELDS = List.of("inCommunicationError", "inEquipmentError",
            "isFilterDirty", "isGroupSlave");

    /** 温度单位仍由来源配置控制；压缩机字段名由协议的接口差异决定。 */
    public record FieldPolicy(boolean temperatureCelsiusConfirmed) {
        public static FieldPolicy unconfirmed() { return new FieldPolicy(false); }
    }

    public record Page(int currentPage, int totalPages, int totalCount, List<DaikinDeviceObservation> devices) {
        public Page { devices = List.copyOf(devices); }
    }

    private final FieldPolicy fieldPolicy;

    public DaikinDevicePageDecoder(FieldPolicy fieldPolicy) {
        this.fieldPolicy = Objects.requireNonNull(fieldPolicy);
    }

    public Page decode(String sourceId, DaikinDeviceKey.Kind kind, JsonNode response, Instant observedAt) {
        DaikinDeviceKey.requireIdentity(sourceId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(observedAt);
        requireObject(response);
        if (!response.path("code").isTextual() || !"10000".equals(response.path("code").textValue())) {
            throw invalid();
        }
        JsonNode data = response.path("data");
        requireObject(data);
        int current = count(data, "curPage", 1, 10_000);
        int total = count(data, "totalPages", 0, 10_000);
        int devicesTotal = count(data, "totalCount", 0, 1_000_000);
        if ((total == 0 && (current != 1 || devicesTotal != 0))
                || (total > 0 && current > total)) throw invalid();
        JsonNode sites = array(data, "sites", 100);
        List<DaikinDeviceObservation> result = new ArrayList<>();
        for (JsonNode site : sites) {
            requireObject(site);
            String siteId = identity(site, "siteId");
            for (JsonNode controller : array(site, "controlers", 100)) {
                requireObject(controller);
                String controllerId = identity(controller, "lcNo");
                for (JsonNode unit : array(controller, "units", 100)) {
                    requireObject(unit);
                    if (result.size() >= 100) throw invalid();
                    DaikinDeviceKey key = new DaikinDeviceKey(sourceId, siteId, controllerId, kind,
                            identity(unit, "unitId"));
                    Map<String, DaikinFieldValue> fields = decodeFields(kind, unit, controller);
                    result.add(new DaikinDeviceObservation(key, optionalIdentity(unit, "id"),
                            optionalText(site, "siteName"), optionalText(unit, "name"), observedAt,
                            optionalText(response, "resTime"), fields));
                }
            }
        }
        if (result.size() > devicesTotal || (devicesTotal == 0 && !result.isEmpty())) throw invalid();
        return new Page(current, total, devicesTotal, result);
    }

    private Map<String, DaikinFieldValue> decodeFields(DaikinDeviceKey.Kind kind, JsonNode unit,
                                                       JsonNode controller) {
        Map<String, DaikinFieldValue> fields = new LinkedHashMap<>();
        fields.put("controller.isConnectionUp", booleanField(controller.get("isConnectionUp")));
        fields.put("controller.inForcedStop", booleanField(controller.get("inForcedStop")));
        fields.put("controller.status", textField(controller.get("status")));
        fields.put("controller.decommissioned", candidate(controller.get("decommissioned")));
        fields.put("formalName", textField(unit.get("formalName")));
        fields.put("modelName", textField(unit.get("modelName")));
        if (kind == DaikinDeviceKey.Kind.OUTDOOR) {
            fields.put("compressorOnOff", compressorOnOff(unit.get("mc11"), unit.get("Mc11")));
            return fields;
        }
        ENUMS.forEach((name, allowed) -> fields.put(name, enumField(unit.get(name), allowed)));
        BOOLEAN_FIELDS.forEach(name -> fields.put(name, booleanField(unit.get(name))));
        // 真实厂家字段为 inMaintenanceMode；沿用已落库的内部键，避免切断既有查询和异常投影。
        fields.put("inMantenanceMode", booleanField(unit.has("inMaintenanceMode")
                ? unit.get("inMaintenanceMode") : unit.get("inMantenanceMode")));
        fields.put("roomTemp", temperature(unit.get("roomTemp")));
        fields.put("temperature", temperature(unit.get("temperature")));
        fields.put("errorCode", textField(unit.get("errorCode")));
        JsonNode errorType = unit.get("errorType");
        fields.put("errorType", missing(errorType) ? absent()
                : errorType.isIntegralNumber() && errorType.canConvertToInt()
                && errorType.intValue() >= 0 && errorType.intValue() <= 2
                ? present(errorType, errorType.asText()) : new DaikinFieldValue(INVALID, raw(errorType), null));
        // 协议给出了权限枚举与设温边界的类型；只解码符合协议的值，不推断设备控制能力。
        PROTOCOL_ENUMS.forEach((name, allowed) -> fields.put(name, enumField(unit.get(name), allowed)));
        SETPOINT_LIMIT_FIELDS.forEach(name -> fields.put(name, setpointLimit(unit.get(name))));
        fields.put("arth1", candidate(unit.get("arth1")));
        fields.put("fanSpeedSetList", capabilityList(unit.get("fanSpeedSetList"), ENUMS.get("fanSpeed")));
        fields.put("modeSetList", capabilityList(unit.get("modeSetList"), ENUMS.get("mode")));
        fields.put("onOffModeSetList", capabilityList(unit.get("onOffModeSetList"), ENUMS.get("onOff")));
        fields.put("DefaultSetpointRange", setpointRange(unit.get("DefaultSetpointRange")));
        fields.put("masterSlaveIds", relatedIds(unit.get("masterSlaveIds")));
        return fields;
    }

    private DaikinFieldValue temperature(JsonNode value) {
        if (missing(value)) return absent();
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || raw(value) == null) {
            return new DaikinFieldValue(INVALID, raw(value), null);
        }
        // 这是序列化资源边界，不是温度专业阈值；禁止巨大指数展开为无界十进制字符串。
        if (value.decimalValue().precision() > 32 || Math.abs((long) value.decimalValue().scale()) > 32) {
            return new DaikinFieldValue(INVALID, raw(value), null);
        }
        return fieldPolicy.temperatureCelsiusConfirmed()
                ? present(value, value.decimalValue().toPlainString())
                : new DaikinFieldValue(UNCONFIRMED, raw(value), null);
    }

    private static DaikinFieldValue enumField(JsonNode value, Set<String> allowed) {
        if (missing(value)) return absent();
        if (!value.isTextual() || raw(value) == null) return new DaikinFieldValue(INVALID, raw(value), null);
        // 厂家同一枚举可能使用不同大小写；仅匹配已知成员，保留原始报文供追溯。
        return allowed.stream().filter(candidate -> candidate.equalsIgnoreCase(value.textValue()))
                .findFirst().map(candidate -> present(value, candidate))
                .orElseGet(() -> new DaikinFieldValue(UNKNOWN, raw(value), null));
    }

    private static DaikinFieldValue compressorOnOff(JsonNode listValue, JsonNode detailValue) {
        if (missing(listValue) && missing(detailValue)) return absent();
        JsonNode selected = missing(listValue) ? detailValue : listValue;
        DaikinFieldValue decoded = compressorValue(selected);
        if (!missing(listValue) && !missing(detailValue)) {
            DaikinFieldValue other = compressorValue(detailValue);
            if (decoded.status() != PRESENT || other.status() != PRESENT
                    || !decoded.normalizedValue().equals(other.normalizedValue())) {
                return new DaikinFieldValue(INVALID, null, null);
            }
        }
        return decoded;
    }

    private static DaikinFieldValue compressorValue(JsonNode value) {
        // 协议表写 Integer，示例使用 on/off；0/1 是通用二值约定，原始报文始终保留。
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            if (value.intValue() == 0) return present(value, "off");
            if (value.intValue() == 1) return present(value, "on");
        }
        if (value.isTextual()) return enumField(value, Set.of("on", "off"));
        return new DaikinFieldValue(value.isNumber() ? UNKNOWN : INVALID, raw(value), null);
    }

    private static DaikinFieldValue booleanField(JsonNode value) {
        if (missing(value)) return absent();
        return value.isBoolean() ? present(value, value.asText()) : new DaikinFieldValue(INVALID, raw(value), null);
    }

    private static DaikinFieldValue setpointLimit(JsonNode value) {
        if (missing(value)) return absent();
        // 厂家以 16.0/32.0 传输整数设温；按数值检查整数性，不能按 JSON 节点类型拒收。
        return boundedNumber(value) && value.decimalValue().stripTrailingZeros().scale() <= 0
                && value.doubleValue() >= 16 && value.doubleValue() <= 32
                ? present(value, value.decimalValue().stripTrailingZeros().toPlainString())
                : new DaikinFieldValue(INVALID, raw(value), null);
    }

    private static boolean boundedNumber(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue()) && raw(value) != null
                && value.decimalValue().precision() <= 32 && Math.abs((long) value.decimalValue().scale()) <= 32;
    }

    /** 能力列表兼容厂家方括号字符串与 JSON 数组；只读枚举不产生控制权限。 */
    private static DaikinFieldValue capabilityList(JsonNode value, Set<String> allowed) {
        if (missing(value)) return absent();
        if (raw(value) == null) return new DaikinFieldValue(INVALID, null, null);
        JsonNode items = value;
        if (value.isTextual()) {
            String text = value.textValue().trim();
            if (!text.startsWith("[") || !text.endsWith("]")) return new DaikinFieldValue(INVALID, raw(value), null);
            var array = JsonNodeFactory.instance.arrayNode();
            String body = text.substring(1, text.length() - 1).trim();
            if (!body.isEmpty()) for (String token : body.split(",", -1)) array.add(token.trim());
            items = array;
        }
        if (!items.isArray() || items.size() > 100) return new DaikinFieldValue(INVALID, raw(value), null);
        var normalized = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : items) {
            if (!item.isTextual() || item.textValue().isBlank()) return new DaikinFieldValue(INVALID, raw(value), null);
            var decoded = enumField(item, allowed);
            if (decoded.status() != PRESENT) return new DaikinFieldValue(decoded.status(), raw(value), null);
            normalized.add(decoded.normalizedValue());
        }
        String text = normalized.toString();
        return text.length() <= 1024 ? present(value, text) : new DaikinFieldValue(INVALID, raw(value), null);
    }

    private static DaikinFieldValue setpointRange(JsonNode value) {
        if (missing(value)) return absent();
        if (!value.isObject() || raw(value) == null) return new DaikinFieldValue(INVALID, raw(value), null);
        var min = value.get("min");
        var max = value.get("max");
        var step = value.get("step");
        if (!boundedNumber(min) || !boundedNumber(max) || !boundedNumber(step)
                || min.decimalValue().compareTo(max.decimalValue()) > 0 || step.decimalValue().signum() <= 0) {
            return new DaikinFieldValue(INVALID, raw(value), null);
        }
        var normalized = JsonNodeFactory.instance.objectNode();
        normalized.put("min", min.decimalValue().stripTrailingZeros());
        normalized.put("max", max.decimalValue().stripTrailingZeros());
        normalized.put("step", step.decimalValue().stripTrailingZeros());
        return present(value, normalized.toString());
    }

    /** 设备编号保留为字符串，避免前端数值精度损失；关联关系不推断内外机物理连接。 */
    private static DaikinFieldValue relatedIds(JsonNode value) {
        if (missing(value)) return absent();
        if (!value.isArray() || value.size() > 100 || raw(value) == null) return new DaikinFieldValue(INVALID, raw(value), null);
        var normalized = JsonNodeFactory.instance.arrayNode();
        for (JsonNode id : value) {
            if (!(id.isTextual() || id.isIntegralNumber()) || !id.asText().matches("[A-Za-z0-9_.:-]{1,200}")) {
                return new DaikinFieldValue(INVALID, raw(value), null);
            }
            normalized.add(id.asText());
        }
        String text = normalized.toString();
        return text.length() <= 1024 ? present(value, text) : new DaikinFieldValue(INVALID, raw(value), null);
    }

    private static DaikinFieldValue textField(JsonNode value) {
        if (missing(value)) return absent();
        return value.isTextual() && raw(value) != null ? present(value, value.textValue())
                : new DaikinFieldValue(INVALID, raw(value), null);
    }

    private static DaikinFieldValue candidate(JsonNode value) {
        if (missing(value)) return absent();
        String json = raw(value);
        return new DaikinFieldValue(json == null ? INVALID : UNCONFIRMED, json, null);
    }

    private static DaikinFieldValue present(JsonNode value, String normalized) {
        return new DaikinFieldValue(PRESENT, raw(value), normalized);
    }

    private static boolean missing(JsonNode value) { return value == null || value.isNull(); }
    private static DaikinFieldValue absent() { return new DaikinFieldValue(MISSING, null, null); }
    private static String raw(JsonNode value) {
        if (value == null) return null;
        String json = value.toString();
        return json.length() <= 1024 ? json : null;
    }

    private static int count(JsonNode parent, String name, int min, int max) {
        JsonNode value = parent.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min
                || value.intValue() > max) throw invalid();
        return value.intValue();
    }

    private static JsonNode array(JsonNode parent, String name, int max) {
        JsonNode value = parent.path(name);
        if (!value.isArray() || value.size() > max) throw invalid();
        return value;
    }

    private static String optionalIdentity(JsonNode parent, String name) {
        return missing(parent.get(name)) ? null : identity(parent, name);
    }

    private static String identity(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!(value.isTextual() || value.isIntegralNumber())) throw invalid();
        String text = value.asText();
        if (text.isBlank() || text.length() > 200 || !text.equals(text.strip())
                || text.chars().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    private static String optionalText(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (missing(value)) return null;
        if (!value.isTextual() || value.textValue().length() > 256) throw invalid();
        return value.textValue();
    }

    private static void requireObject(JsonNode node) { if (node == null || !node.isObject()) throw invalid(); }
    private static IllegalArgumentException invalid() {
        // 不拼接厂家响应，避免异常日志记录未脱敏身份或返回内容。
        return new IllegalArgumentException("DAIKIN_DEVICE_PAGE_INVALID");
    }
}
