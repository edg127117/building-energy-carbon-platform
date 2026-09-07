package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.TreeSet;
import java.util.Set;

/** 固定计算时的证据，以十进制字符串避免 MySQL JSON 浮点转换损失核算精度。 */
public final class CarbonEvidence {
    private static final Set<String> SHARED_FIELDS = Set.of("factor", "factorSources", "formula",
            "roundingPolicy", "areaDenominator", "populationDenominator", "gwp", "matching", "calculation");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private CarbonEvidence() { }

    public static ObjectNode object() { return JSON.createObjectNode(); }

    public static JsonNode tree(Object value) { return JSON.valueToTree(value); }

    public static JsonNode parse(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("碳追溯证据不是有效 JSON", exception);
        }
    }

    public static String canonical(JsonNode value) {
        try {
            return JSON.writeValueAsString(ordered(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("碳追溯证据无法序列化", exception);
        }
    }

    /** 历史记录仅标明证据边界，不以当前上游或规则填补当时未保存的事实。 */
    public static JsonNode stored(String json, String hash) {
        return stored(json, hash, null);
    }

    /** 共享内容是同批次提交的固定事实，不从当前规则表补齐；完整明细仍使用原始摘要校验。 */
    public static JsonNode stored(String json, String hash, String sharedJson) {
        JsonNode value = parse(json);
        if (value.has("sharedReferences")) {
            JsonNode shared = sharedJson == null ? object() : parse(sharedJson);
            JsonNode references = ((ObjectNode) value).remove("sharedReferences");
            references.fields().forEachRemaining(entry -> {
                JsonNode content = shared.get(entry.getValue().asText());
                if (!SHARED_FIELDS.contains(entry.getKey()) || content == null) {
                    throw new IllegalStateException("碳追溯共享证据缺失或引用无效");
                }
                ((ObjectNode) value).set(entry.getKey(), content);
            });
        }
        if (!value.has("schemaVersion")) {
            ObjectNode legacy = object();
            legacy.put("traceStatus", "LEGACY_PARTIAL");
            legacy.set("originalEvidence", value);
            return legacy;
        }
        if (!CarbonCalculationCore.sha256(canonical(value)).equals(hash)) {
            throw new IllegalStateException("碳追溯证据摘要不一致");
        }
        return value;
    }

    /** 将重复规则归并到批次内的内容寻址字典，活动量和结果仍保存在各自明细。 */
    static String compact(ObjectNode full, ObjectNode shared) {
        ObjectNode stored = full.deepCopy();
        ObjectNode references = stored.putObject("sharedReferences");
        SHARED_FIELDS.forEach(field -> {
            JsonNode content = stored.remove(field);
            if (content == null) return;
            String key = CarbonCalculationCore.sha256(canonical(content));
            shared.set(key, content);
            references.put(field, key);
        });
        return canonical(stored);
    }

    private static JsonNode ordered(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = object();
            TreeSet<String> names = new TreeSet<>();
            value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, ordered(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            value.forEach(element -> result.add(ordered(element)));
            return result;
        }
        if (value.isFloatingPointNumber()) {
            return TextNode.valueOf(value.decimalValue().stripTrailingZeros().toPlainString());
        }
        return value;
    }
}
