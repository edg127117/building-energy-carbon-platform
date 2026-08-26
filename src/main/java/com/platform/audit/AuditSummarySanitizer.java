package com.platform.audit;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
/** 对进入审计表的短摘要执行最后一道凭据遮蔽和长度限制。 */
public class AuditSummarySanitizer {
    private static final int MAX_LENGTH = 1000;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|token|cookie|secret|private[_-]?key|credential)\\s*[:=]\\s*([^;,}\\s]+)");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    public String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = CONTROL.matcher(value).replaceAll("");
        cleaned = SECRET.matcher(cleaned).replaceAll("$1=***");
        return cleaned.length() <= MAX_LENGTH ? cleaned : cleaned.substring(0, MAX_LENGTH);
    }
}
