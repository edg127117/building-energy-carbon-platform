package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定基础设施 Compose 边界，数据库结构由应用内 Flyway 单一治理。 */
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
                        "name: ${TDENGINE_VOLUME_NAME:-iot-platform-demo-tdengine-data}")
                .doesNotContain("- ./taos-data:/var/lib/taos");
        assertThat(countOccurrences(compose, "tdengine-data:")).isEqualTo(2);
    }

    @Test
    void mysqlAndRedisUseProjectIndependentVolumes() {
        assertThat(compose)
                .contains(
                        "- mysql-data:/var/lib/mysql",
                        "name: ${MYSQL_VOLUME_NAME:-iot-platform-demo-mysql-data}",
                        "- redis-data:/data",
                        "name: ${REDIS_VOLUME_NAME:-iot-platform-demo-redis-data}")
                .doesNotContain(
                        "- ./mysql-data:/var/lib/mysql",
                        "- ./redis-data:/data");
        assertThat(countOccurrences(compose, "mysql-data:")).isEqualTo(2);
        assertThat(countOccurrences(compose, "redis-data:")).isEqualTo(2);
    }

    @Test
    void mysqlContainerDoesNotOwnSchemaInitialization() {
        assertThat(compose)
                .doesNotContain(":/docker-entrypoint-initdb.d")
                .doesNotContain(".sql:/");
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
