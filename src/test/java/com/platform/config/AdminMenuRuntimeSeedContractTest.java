package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定新库种子与已有库手工迁移的真实前端路由和固定角色边界。 */
class AdminMenuRuntimeSeedContractTest {

    private final String baseSql = read("src/env/init/03-init-hvac-schema.sql");
    private final String migrationSql = read("src/env/init/11-migrate-mysql-admin-menu-runtime.sql");

    @Test
    void baseSeedContainsOnlyConfirmedRuntimeCatalogAsVisibleEntries() {
        assertThat(baseSql)
                .contains("(101", "'/hvac-demo'", "(223", "'/system/building-access'",
                        "'/system/users'", "'/system/roles'", "'/system/menus'",
                        "'单机调适',    'M', '/single',        NULL,       'control',   0, 0",
                        "'建筑注册',    'C', '/system/building/list', 'system/BuildingList','home',   0, 0")
                .contains("'BUILDING_OWNER'", "'ENERGY_MANAGER'", "'PLATFORM_ADMIN'");
    }

    @Test
    void existingDatabaseMigrationIsIdempotentAndPreservesMenuRows() {
        assertThat(migrationSql)
                .contains("ON DUPLICATE KEY UPDATE", "INSERT IGNORE", "visible`=0, `status`=0",
                        "BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN",
                        "SET `data_scope`='BUILDING' WHERE `role_key`='THIRD_PARTY'",
                        "WHERE r.`role_key`='THIRD_PARTY'")
                .doesNotContain("DELETE FROM `sys_menu`", "DROP TABLE", "TRUNCATE TABLE");
    }

    private String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取菜单运行时 SQL: " + path, e);
        }
    }
}
