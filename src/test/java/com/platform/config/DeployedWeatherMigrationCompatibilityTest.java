package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** 已应用迁移必须保持原始内容；真实 Flyway 历史兼容另在服务器隔离库验收。 */
class DeployedWeatherMigrationCompatibilityTest {
    @Test void preservesDeployedWeatherV67BytesAndKeepsControlVersionsSeparate() throws Exception {
        var root = Path.of("src/env/init");
        byte[] deployed = Files.readAllBytes(root.resolve("V67__mysql_weather_frontend_menus.sql"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(deployed)))
                .isEqualTo("9583dc9d9f1ef69b2d88d8a8611b8941ecdf3ddc15343ab92efb9fb36aac6bc1");
        assertThat(root.resolve("V68__mysql_daikin_control.sql")).exists();
        assertThat(root.resolve("V69__mysql_daikin_control_stability.sql")).exists();
        assertThat(root.resolve("V67__mysql_daikin_control.sql")).doesNotExist();
        assertThat(root.resolve("V68__mysql_daikin_control_stability.sql")).doesNotExist();
    }
}
