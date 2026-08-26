package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuditGovernanceMigrationContractTest {
    @Test
    void migrationCreatesFixedDutiesWithoutAutoGrantingAdministrators() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(
                "/db/migration/mysql/V25__mysql_audit_governance_foundation.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("sys_backend_duty", "sys_user_backend_duty",
                "sys_sensitive_change_request", "sys_security_audit_event",
                "BACKOFFICE_CHANGE_SUBMITTER", "AUDIT_EVIDENCE_HOLD_MANAGER");
        assertThat(sql).doesNotContain("INSERT INTO `sys_user_backend_duty`");
        assertThat(sql).contains("idx_security_audit_retention", "idx_sensitive_change_trace");
    }
}
