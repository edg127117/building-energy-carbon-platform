package com.platform.iot.daikin.mapping;

import com.fasterxml.jackson.databind.JsonNode;
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
                    "equipmentErrorStopped", "communicationError", "maintenanceMode", "forcedStop"),
            "masterSlaveFlag", Set.of("Master", "Slave"));
    private static final List<String> BOOLEAN_FIELDS = List.of("inCommunicationError", "inEquipmentError",
            "isFilterDirty", "isGroupSlave");
    private static final List<String> UNCONFIRMED_FIELDS = List.of("arth1", "rcProhibitOnOff",
            "rcProhibitOpMode", "rcProhibitSetpoint", "limitSettempHeat", "limitSettempCool",
            "coolLimitsettempU", "coolLimitsettempL", "heatLimitsettempU", "heatLimitsettempL",
            "fanSpeedSetList", "modeSetList", "onOffModeSetList", "DefaultSetpointRange", "masterSlaveIds");

    /** 来源适配器按该项目证据显式传入单位和压缩机字段策略；本解码器默认仍保留未确认状态。 */
    public record FieldPolicy(boolean temperatureCelsiusConfirmed, String compressorField) {
        public FieldPolicy {
            if (compressorField != null && !Set.of("mc11", "Mc11").contains(compressorField)) {
                throw new IllegalArgumentException("压缩机字段映射无效");
            }
        }
        public static FieldPolicy unconfirmed() { return new FieldPolicy(false, null); }
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
        fields.put("controller.status", candidate(controller.get("status")));
        fields.put("controller.decommissioned", candidate(controller.get("decommissioned")));
        fields.put("formalName", textField(unit.get("formalName")));
        fields.put("modelName", textField(unit.get("modelName")));
        if (kind == DaikinDeviceKey.Kind.OUTDOOR) {
            String mapped = fieldPolicy.compressorField();
            // 大小写冲突不能择一掩盖，未确认前保留两个候选字段而不推断压缩机状态。
            if (mapped == null) {
                fields.put("mc11", candidate(unit.get("mc11")));
                fields.put("Mc11", candidate(unit.get("Mc11")));
            } else {
                JsonNode selected = unit.get(mapped);
                JsonNode alternate = unit.get(mapped.equals("mc11") ? "Mc11" : "mc11");
                fields.put("compressorOnOff", alternate != null && !alternate.equals(selected)
                        ? new DaikinFieldValue(INVALID, null, null)
                        : enumField(selected, Set.of("on", "off")));
            }
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
        UNCONFIRMED_FIELDS.forEach(name -> fields.put(name, candidate(unit.get(name))));
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

    private static DaikinFieldValue booleanField(JsonNode value) {
        if (missing(value)) return absent();
        return value.isBoolean() ? present(value, value.asText()) : new DaikinFieldValue(INVALID, raw(value), null);
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
