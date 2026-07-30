package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约束清库冒烟脚本的破坏边界，防止后续维护时扩大到仓库或其他 Docker 项目。
 */
class CleanHvacSmokeScriptContractTest {

    private final String script = readScript();

    @Test
    void destructiveExecutionRequiresExplicitResetFlagAndLiteralPaths() {
        assertThat(script).contains(
                "[switch]$ResetData",
                "LegacyEnvRoot",
                "[IO.Path]::GetFullPath",
                "Remove-Item -LiteralPath",
                "mysql-data",
                "taos-data",
                "redis-data");
    }

    @Test
    void scriptOnlyTargetsTheFourKnownContainers() {
        assertThat(script).contains(
                "'iot-mysql'",
                "'iot-emqx'",
                "'iot-tdengine'",
                "'iot-redis'");
        assertThat(script).doesNotContain(
                "docker system prune",
                "docker volume prune",
                "Remove-Item $repoRoot",
                "Remove-Item -Recurse -Force $env:");
    }

    @Test
    void scriptRequiresAllRuntimeAndLegacyAssertions() {
        assertThat(script).contains(
                "iot_device",
                "iot_device_status_log",
                "control_commands",
                "st_electric_data",
                "st_raw_event",
                "st_raw_minute",
                "st_indicator_minute",
                "st_formula_calc_exception",
                "CLEAN_HVAC_SMOKE_SUCCESS");
    }

    private String readScript() {
        try {
            return Files.readString(
                    Path.of("scripts/Invoke-CleanHvacSmoke.ps1"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取干净环境冒烟脚本", e);
        }
    }
}
