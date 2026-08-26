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
                "src/env/init/V22__mysql_telemetry_reliability_v2.sql"));

        assertThat(migration).contains("max_ack_mode", "correlation_policy",
                "biz_telemetry_receipt", "biz_telemetry_receipt_failure",
                "canonical_message_id", "payload_hash",
                "platform_consumer_ack_state", "application_ack_puback_state");
        assertThat(migration).contains(
                "migrate_telemetry_reliability_v2_identity",
                "information_schema.`COLUMNS`",
                "COLUMN_NAME`='max_ack_mode'",
                "COLUMN_NAME`='adapter_ack_topic'");
        assertThat(migration).contains("biz_mqtt_failure_aggregate", "occurrence_count");
        assertThat(migration).doesNotContain("raw_payload");
    }

    @Test
    void v23RemovesPerMessageAckSuccessEvidenceAndAddsCleanupIndexes()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/env/init/V23__optimize_telemetry_receipt_retention.sql"));

        assertThat(migration).contains(
                "DROP INDEX `idx_receipt_status_persisted`",
                "DROP COLUMN `device_puback_state`",
                "DROP COLUMN `adapter_publish_puback_state`",
                "DROP COLUMN `platform_consumer_ack_state`",
                "DROP COLUMN `application_ack_puback_state`",
                "DROP COLUMN `application_ack_published_at`",
                "ADD INDEX `idx_receipt_cleanup` (`persisted_at`, `canonical_message_id`)",
                "ADD INDEX `idx_receipt_failure_occurred` (`occurred_at`)");
        assertThat(migration).doesNotContain("ALTER TABLE `biz_telemetry_receipt` ADD COLUMN");
    }
}
