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
    void onlyExplicitDeviceIngressCanLeaveLoopbackAndRedisMustAuthenticate() throws IOException {
        assertThat(compose).contains("127.0.0.1:${MYSQL_PORT:-3306}:3306",
                "127.0.0.1:${REDIS_PORT:-6379}:6379", "127.0.0.1:${TDENGINE_REST_PORT:-6041}:6041",
                "127.0.0.1:${EMQX_DASHBOARD_PORT:-18083}:18083", "${MQTT_BIND_ADDRESS:-127.0.0.1}")
                .doesNotContain("6030-6049", "--requirepass", "${MYSQL_PASSWORD:-change-me}");
        var permissions = Files.readString(Path.of("src/env/redis-app.permissions"));
        assertThat(permissions).contains("~iot:*", "+evalsha", "+script|load", "+get", "+del")
                .doesNotContain("+@all", "+flushdb", "+config", "~*");
        assertThat(Files.readString(Path.of("src/env/redis-start.sh")))
                .contains("user default off", "sha256sum", "--aclfile");
        assertThat(compose).contains("REDIS_PASSWORD:?", "EMQX_DASHBOARD_PASSWORD:?", "MYSQL_ROOT_PASSWORD:?")
                .contains("EMQX_AUTHENTICATION__1__BACKEND: built_in_database");
    }

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
