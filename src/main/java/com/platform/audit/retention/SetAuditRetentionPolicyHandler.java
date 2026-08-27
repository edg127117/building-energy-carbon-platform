package com.platform.audit.retention;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
/** 保留策略只能作为白名单敏感命令审核执行，并以新版本替代旧版本。 */
public class SetAuditRetentionPolicyHandler implements SensitiveOperationHandler {
    public static final String OPERATION_CODE = "SET_AUDIT_RETENTION_POLICY";
    private static final Pattern KEY = Pattern.compile("[A-Z0-9_]{1,64}");

    private final ObjectMapper objectMapper;
    private final AuditRetentionPolicyService service;
    private final java.util.List<AuditRetentionHandler> handlers;

    @Override
    public String operationCode() {
        return OPERATION_CODE;
    }

    @Override
    public Set<BackendDuty> requiredSubmitterDuties() {
        return Set.of(BackendDuty.AUDIT_RETENTION_MANAGER);
    }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        try {
            RawCommand raw = objectMapper.readerFor(RawCommand.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(command);
            String source = key(raw.sourceModule(), "审计来源无效");
            if (handlers.stream().noneMatch(handler -> handler.sourceModule().equals(source))) {
                throw AuditRetentionPolicyService.invalid("审计来源无效");
            }
            String category = key(raw.dataCategory(), "数据类别无效");
            String period = period(raw.retentionPeriod());
            LocalDateTime effectiveAt = raw.effectiveAt();
            String reason = text(raw.changeReason(), 500, "策略变更原因无效");
            if (effectiveAt == null || raw.cleanupEnabled() == null) {
                throw AuditRetentionPolicyService.invalid("策略生效时间和启用状态不能为空");
            }
            var normalized = new AuditRetentionPolicyService.RetentionPolicyCommand(
                    category, source, period, Boolean.TRUE.equals(raw.cleanupEnabled()), effectiveAt, reason);
            return new NormalizedSensitiveCommand(null, "AUDIT_RETENTION_POLICY", source,
                    objectMapper.writeValueAsString(normalized),
                    "source=" + source + ";period=" + period + ";enabled=" + normalized.cleanupEnabled());
        } catch (IOException | IllegalArgumentException failure) {
            throw AuditRetentionPolicyService.invalid("保留策略命令无效");
        }
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        try {
            var value = objectMapper.readValue(command.canonicalJson(),
                    AuditRetentionPolicyService.RetentionPolicyCommand.class);
            service.apply(value, context.requestId(), context.submitterId(), context.reviewerId());
            return SensitiveOperationResult.none();
        } catch (IOException failure) {
            throw AuditRetentionPolicyService.invalid("保留策略命令无效");
        }
    }

    private static String period(String value) {
        try {
            Period parsed = Period.parse(text(value, 20, "保留期限无效").toUpperCase(Locale.ROOT));
            if (parsed.isZero() || parsed.isNegative() || parsed.getDays() != 0
                    || parsed.toTotalMonths() < 1 || parsed.toTotalMonths() > 120) {
                throw AuditRetentionPolicyService.invalid("保留期限必须是1至120个月的日历期限");
            }
            return parsed.normalized().toString();
        } catch (java.time.format.DateTimeParseException failure) {
            throw AuditRetentionPolicyService.invalid("保留期限必须使用P6M或P1Y等ISO日历格式");
        }
    }

    private static String key(String value, String message) {
        String normalized = text(value, 64, message).toUpperCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) throw AuditRetentionPolicyService.invalid(message);
        return normalized;
    }

    private static String text(String value, int max, String message) {
        if (value == null || value.isBlank()) throw AuditRetentionPolicyService.invalid(message);
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw AuditRetentionPolicyService.invalid(message);
        }
        return normalized;
    }

    private record RawCommand(
            String dataCategory, String sourceModule, String retentionPeriod,
            Boolean cleanupEnabled, LocalDateTime effectiveAt, String changeReason) {
    }
}
