package com.platform.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 静态迁移契约不替代目标 MySQL 上的过程执行验收。 */
class WeatherMenuMigrationContractTest {
    @Test void guardsHierarchyAndUsesGeneratedIdsWithExplicitRoleGrants() throws Exception {
        String sql = Files.readString(Path.of("src/env/init/V67__mysql_weather_frontend_menus.sql"));
        assertThat(sql).contains("WEATHER_MENU_PARENT_MISSING", "WEATHER_MENU_PATH_CONFLICT",
                "WEATHER_MENU_HIERARCHY_CONFLICT", "GROUP BY path HAVING COUNT(*) > 1",
                "NOT EXISTS", "INSERT IGNORE INTO sys_role_menu",
                "r.role_key IN ('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')",
                "r.role_key IN ('ENERGY_MANAGER','PLATFORM_ADMIN')");
        assertThat(sql).contains("@bec_weather_menu_new=1", "root.path='/operations' AND sibling.sort_order>=3");
        assertThat(sql).doesNotContain("INSERT INTO sys_menu (id", "DELETE FROM");
    }
}
