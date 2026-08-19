package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 约束硬件 24 小时验收脚本保持只读，并覆盖运行状态与完整验收时间范围。 */
class HardwareAcceptanceMonitorScriptContractTest {

    private final String script = readScript();

    @Test
    void requiresActualAcceptanceStartAndQueriesBothTdengineStages() {
        assertThat(script).contains(
                "--started-at",
                "monitor_started_after_acceptance_seconds",
                "iot_telemetry.st_raw_event",
                "iot_telemetry.st_raw_minute",
                "POINT004,POINT005,POINT006,POINT007",
                "FIRST(ts) AS first_ts",
                "LAST(ts) AS last_ts",
                "FIRST(received_time) AS first_received",
                "LAST(received_time) AS last_received",
                "SUM(sample_count) AS samples");
        assertThat(script).doesNotContain(
                "MIN(ts)",
                "MAX(ts)",
                "MIN(received_time)",
                "MAX(received_time)");
    }

    @Test
    void samplesRuntimeAndPreservesReviewableEvidence() {
        assertThat(script).contains(
                "runtime-samples.csv",
                "app-log-delta.log",
                "tdengine-raw-summary.txt",
                "tdengine-minute-summary.txt",
                "report.md",
                "docker inspect",
                "pgrep -n -f",
                "最终结论仍需确认");
    }

    @Test
    void doesNotRestartServicesOrMutateDatabases() {
        assertThat(script).doesNotContainIgnoringCase(
                "systemctl restart",
                "docker restart",
                "delete from",
                "drop table",
                "truncate table",
                "update ",
                "insert into");
    }

    private String readScript() {
        try {
            return Files.readString(Path.of("scripts/Monitor-HardwareAcceptance24h.sh"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取硬件 24 小时验收脚本", e);
        }
    }
}
