package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AccountCredentialMigrationContractTest {
    @Test
    void migrationStoresOnlyTokenHashAndActivationState() throws Exception {
        String sql = Files.readString(Path.of("src", "env", "init",
                "V26__mysql_account_activation_token.sql"));

        assertThat(sql).contains("activation_pending", "sys_password_setup_token", "token_hash",
                "ACTIVATION", "RESET", "expires_at", "source_request_id");
        assertThat(sql).doesNotContain("raw_token", "plain_password");
    }
}
