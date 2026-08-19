package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 约束合成设备接入冒烟的隔离边界、场景覆盖和清理证据。 */
class DeviceOnboardingSmokeScriptContractTest {

    private final String smoke = read("scripts/Test-DeviceOnboardingSmoke.ps1");
    private final String publisher = read(".scripts/publish-device-onboarding-message.mjs");
    private final String orchestrator = read("scripts/Invoke-CleanHvacSmoke.ps1");

    @Test
    void onlyUsesDedicatedSmokeContainersAndSyntheticIdentity() {
        assertThat(smoke).contains(
                "iot-smoke-mysql",
                "iot-smoke-tdengine",
                "iot-platform-demo-hvac-smoke",
                "Assert-IsolatedContainer",
                "E3A-SYNTHETIC-MAC-001",
                "E3A_SYNTHETIC_WCR");
        assertThat(smoke).doesNotContain(
                "iot-mysql'",
                "iot-tdengine'",
                "docker system prune",
                "docker volume prune");
    }

    @Test
    void coversDiscoveryBindingLifecycleAndNegativePackets() {
        assertThat(smoke).contains(
                "unknown discovery",
                "bind but keep inactive",
                "activate and ingest",
                "duplicate idempotency",
                "missing field rejection",
                "profile conflict rejection",
                "deactivate and reject next packet",
                "DEVICE_ONBOARDING_SYNTHETIC_FLOW_OK");
        assertThat(publisher).contains(
                "device/telemetry/up",
                "missing-metrics",
                "HVAC_DEVICE_V1",
                "qos: 1",
                "broker accepted the packet");
    }

    @Test
    void cleansBothDatabasesAndIsIntegratedIntoCleanSmoke() {
        assertThat(smoke).contains(
                "finally",
                "Remove-SyntheticFacts",
                "DROP TABLE IF EXISTS iot_telemetry.st_raw_event_",
                "DROP TABLE IF EXISTS iot_telemetry.st_raw_minute_",
                "DEVICE_ONBOARDING_SYNTHETIC_CLEANUP_OK");
        assertThat(orchestrator).contains(
                "Invoke-OnboardingMigrations",
                "13-migrate-device-onboarding-discovery.sql",
                "14-migrate-device-onboarding-binding.sql",
                "15-migrate-mysql-asset-management-menu.sql",
                "16-migrate-mysql-device-onboarding-menu.sql",
                "Invoke-DeviceOnboardingSmoke");
    }

    private String read(String file) {
        try {
            return Files.readString(Path.of(file));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取设备接入冒烟文件: " + file, exception);
        }
    }
}
