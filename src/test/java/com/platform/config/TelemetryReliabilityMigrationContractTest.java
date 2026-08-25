package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryReliabilityMigrationContractTest {

    @Test
    void migrationDefinesAckCapabilitiesAndOneTerminalReceiptRow() throws IOException {
        String migration = Files.readString(Path.of(
                "src/env/init/20-migrate-mysql-telemetry-reliability-v2.sql"));

        assertThat(migration).contains("max_ack_mode", "correlation_policy",
                "biz_telemetry_receipt", "biz_telemetry_receipt_failure",
                "canonical_message_id", "payload_hash",
                "platform_consumer_ack_state", "application_ack_puback_state");
        assertThat(migration).contains("biz_mqtt_failure_aggregate", "occurrence_count");
        assertThat(migration).doesNotContain("raw_payload");
    }
}
