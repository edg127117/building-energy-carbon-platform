package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class DaikinOperationsMenuMigrationTest {
    @Test
    void movesOnlyPendingLeafAndPreservesGrantsAndDisabledState() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:menu-relocation;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE sys_menu(id INT,parent_id INT,path VARCHAR(200),menu_name VARCHAR(100),visible INT,status INT)");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id INT,menu_id INT)");
        jdbc.execute("INSERT INTO sys_menu VALUES (254,250,'/system/device-onboarding','old',0,0),(255,250,'/configuration/ingestion/protocols','protocol',1,1)");
        jdbc.execute("INSERT INTO sys_role_menu VALUES (3,254),(4,255)");
        var grants = jdbc.queryForList("SELECT * FROM sys_role_menu");
        jdbc.execute(Files.readString(Path.of("src/env/init/V55__mysql_operations_pending_menu.sql")));
        assertThat(jdbc.queryForObject("SELECT path FROM sys_menu WHERE id=254", String.class)).isEqualTo("/operations/devices/pendingDevices");
        assertThat(jdbc.queryForObject("SELECT visible+status FROM sys_menu WHERE id=254", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT path FROM sys_menu WHERE id=255", String.class)).isEqualTo("/configuration/ingestion/protocols");
        assertThat(jdbc.queryForList("SELECT * FROM sys_role_menu")).isEqualTo(grants);
    }
}
