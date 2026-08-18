package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定工作包 C 两条受控页面路由及平台管理员菜单边界。 */
class AssetManagementMenuSeedContractTest {
    private final String migration = read("src/env/init/15-migrate-mysql-asset-management-menu.sql");

    @Test
    void migrationAddsOnlyConfirmedAssetPagesAndRemainsIdempotent() {
        assertThat(migration)
                .contains("'/system/buildings'", "'/system/devices'",
                        "ON DUPLICATE KEY UPDATE", "INSERT IGNORE",
                        "r.`role_key`='PLATFORM_ADMIN'", "r.`role_key`<>'PLATFORM_ADMIN'")
                .doesNotContain("DROP TABLE", "TRUNCATE TABLE", "DELETE FROM `sys_menu`");
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取资产管理菜单迁移: " + path, exception);
        }
    }
}
