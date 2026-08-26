package com.platform.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSummarySanitizerTest {
    private final AuditSummarySanitizer sanitizer = new AuditSummarySanitizer();

    @Test
    void masksCredentialLikeValuesAndLimitsStoredSummary() {
        String sanitized = sanitizer.sanitize("status=ACTIVE; password=plain; token:abc;" + "x".repeat(1200));

        assertThat(sanitized).contains("password=***", "token=***")
                .doesNotContain("plain", "abc")
                .hasSize(1000);
    }
}
