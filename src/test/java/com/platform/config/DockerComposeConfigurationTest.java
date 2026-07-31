package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定空数据环境所使用的 Compose 边界，防止旧电表初始化重新进入部署链路。
 */
class DockerComposeConfigurationTest {

    private final String compose = readCompose();

    @Test
    void composeDoesNotReferenceDeletedMeterInitialization() {
        assertThat(compose)
                .doesNotContain("02-init-10000-devices.sql")
                .doesNotContain("电表数据")
                .doesNotContain("电表电压电流")
                .doesNotStartWith("version:");
    }

    @Test
    void allInfrastructureServicesExposeHealthChecks() {
        assertThat(compose).contains(
                "mysql:",
                "emqx:",
                "tdengine:",
                "redis:");
        assertThat(countOccurrences(compose, "healthcheck:")).isEqualTo(4);
    }

    @Test
    void tdengineUsesStableIdentityAndProjectIndependentVolume() {
        assertThat(compose)
                .contains(
                        "hostname: iot-tdengine",
                        "TAOS_FQDN: iot-tdengine",
                        "TAOS_FIRST_EP: iot-tdengine:6030",
                        "- tdengine-data:/var/lib/taos",
                        "name: iot-platform-demo-tdengine-data")
                .doesNotContain("- ./taos-data:/var/lib/taos");
        assertThat(countOccurrences(compose, "tdengine-data:")).isEqualTo(2);
    }

    private int countOccurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length())
                / needle.length();
    }

    private String readCompose() {
        try {
            return Files.readString(Path.of("src/env/docker-compose.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 Docker Compose 配置", e);
        }
    }
}
