package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 约束真实 HVAC WebSocket 冒烟脚本的认证、范围和清理边界。 */
class HvacRealtimeSmokeScriptContractTest {

    private final String powerShell = read("scripts/Test-HvacRealtimeSmoke.ps1");
    private final String node = read(".scripts/test-hvac-realtime.mjs");
    private final String combined = powerShell + "\n" + node;

    @Test
    void keepsCredentialsOutOfUrlsAndOutput() {
        assertThat(combined)
                .doesNotContain("?token=", "access_token=", "console.log(token)",
                        "Write-Output $adminToken", "Write-Output $restrictedToken")
                .contains("HVAC_RT_ADMIN_TOKEN", "HVAC_RT_RESTRICTED_TOKEN",
                        "[redacted]", "finally");
    }

    @Test
    void usesOnlyProtectedHvacRoutesAndNonDestructivePreparation() {
        assertThat(combined)
                .contains("/ws/hvac", "/hvac/buildings/", "/auth/login")
                .doesNotContain("docker system prune", "docker volume prune",
                        "Remove-Item -Recurse", "/device/list",
                        "/telemetry/history", "/control/issue");
    }

    @Test
    void requiresModernNodeAndChecksRoutingRecoveryAndHttpAuthority() {
        assertThat(combined).contains(
                "typeof fetch", "typeof WebSocket", "SUBSCRIBE", "SUBSCRIBED",
                "4403", "BLD001", "HVAC_INDICATOR", "indicatorCode",
                "minuteStart", "status", "HTTP authoritative reconciliation",
                "reconnect and HTTP reconciliation", "AbortSignal.timeout",
                "HVAC_REALTIME_SMOKE_OK");
    }

    private String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取实时冒烟脚本: " + path, e);
        }
    }
}
