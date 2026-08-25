package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定工作包 D 两个受控页面入口及平台管理员菜单边界。 */
class DeviceOnboardingMenuSeedContractTest {
    private final String migration = read("src/env/init/V16__mysql_device_onboarding_menu.sql");

    @Test
    void migrationAddsOnlyConfirmedOnboardingPagesAndRemainsIdempotent() {
        assertThat(migration)
                .contains("'/system/device-products'", "'/system/device-onboarding'",
                        "ON DUPLICATE KEY UPDATE", "INSERT IGNORE",
                        "DEVICE_ONBOARDING_MENU_ID_OR_PATH_CONFLICT",
                        "(`path`='/system/device-products' AND `id`<>253)",
                        "(`path`='/system/device-onboarding' AND `id`<>254)",
                        "r.`role_key`='PLATFORM_ADMIN'", "r.`role_key`<>'PLATFORM_ADMIN'")
                .doesNotContain("DROP TABLE", "TRUNCATE TABLE", "DELETE FROM `sys_menu`");
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取设备接入菜单迁移: " + path, exception);
        }
    }
}
