package com.platform.audit.query;

import com.platform.audit.AuditGovernanceErrors;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/** 跨审计源稳定排序游标；内容不承载权限，服务端每次翻页仍重新计算职责和建筑范围。 */
public record AuditQueryCursor(Instant operationTime, String auditId, String sourceModule) {
    private static final Pattern KEY = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    public String encode() {
        String value = operationTime.toEpochMilli() + "|" + auditId + "|" + sourceModule;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static AuditQueryCursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3 || !ID.matcher(parts[1]).matches() || !KEY.matcher(parts[2]).matches()) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new AuditQueryCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), parts[1], parts[2]);
        } catch (RuntimeException exception) {
            throw AuditGovernanceErrors.invalidQuery("审计查询游标无效");
        }
    }
}
