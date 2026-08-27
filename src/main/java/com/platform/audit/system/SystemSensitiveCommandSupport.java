package com.platform.audit.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
/** 系统管理敏感命令的严格解析和确定性序列化工具。 */
class SystemSensitiveCommandSupport {
    private final ObjectMapper objectMapper;

    <T> T read(JsonNode command, Class<T> type, String message) {
        if (command == null || !command.isObject()) {
            throw invalid(message);
        }
        try {
            return objectMapper.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(command);
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid(message);
        }
    }

    <T> T readCanonical(String command, Class<T> type, String message) {
        try {
            return objectMapper.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(command);
        } catch (IOException failure) {
            throw invalid(message);
        }
    }

    String canonical(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw invalid(message);
        }
    }

    static long requireId(Long value, String message) {
        if (value == null || value < 1) {
            throw invalid(message);
        }
        return value;
    }

    static String requireText(String value, int maxLength, String message) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maxLength) {
            throw invalid(message);
        }
        return normalized;
    }

    static String optionalText(String value, int maxLength, String message) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw invalid(message);
        }
        return normalized;
    }

    static List<String> strings(Collection<String> values, boolean required, boolean uppercase,
                                int maxItems, int maxLength, String message) {
        if (values == null) {
            if (required) throw invalid(message);
            return List.of();
        }
        if (values.size() > maxItems) throw invalid(message);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = trimToNull(value);
            if (item == null || item.length() > maxLength) throw invalid(message);
            normalized.add(uppercase ? item.toUpperCase(Locale.ROOT) : item);
        }
        if (required && normalized.isEmpty()) throw invalid(message);
        return normalized.stream().sorted().toList();
    }

    static List<Long> ids(Collection<Long> values, int maxItems, String message) {
        if (values == null) return List.of();
        if (values.size() > maxItems) throw invalid(message);
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long value : values) normalized.add(requireId(value, message));
        return normalized.stream().sorted().toList();
    }

    static int flag(Integer value, int defaultValue, String message) {
        int normalized = value == null ? defaultValue : value;
        if (normalized != 0 && normalized != 1) throw invalid(message);
        return normalized;
    }

    static int integer(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    static BusinessException invalid(String message) {
        return new BusinessException(400, AuditGovernanceErrors.REQUEST_CONFLICT, message);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
