package com.platform.iot.energymetadata;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 专用一次性 MySQL 8 验证 V01-V31 完整迁移、复合外键和专业枚举约束。 */
@EnabledIfEnvironmentVariable(named = "ENERGY_METADATA_MYSQL_IT_URL", matches = ".+")
class EnergyPointProfileMysqlIntegrationTest {

    @Test
    void migratesEmptyDatabaseAndEnforcesProfileConstraints() {
        String url = System.getenv("ENERGY_METADATA_MYSQL_IT_URL");
        assertThat(System.getenv("ENERGY_METADATA_MYSQL_IT_ISOLATED"))
                .as("集成测试必须由调用方显式确认使用一次性隔离 MySQL")
                .isEqualTo("true");
        assertThat(url).as("隔离集成测试固定使用项目数据库名").contains("/iot_platform");
        DataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("ENERGY_METADATA_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("ENERGY_METADATA_MYSQL_IT_PASSWORD", "test-root"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class)).as("隔离库必须为空").isZero();

        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").load().migrate();

        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("31");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_energy_point_profile", Integer.class)).isZero();
        insert(jdbc, "MYSQL_PROFILE_OK", "POINT004", "BLD001",
                "ELECTRICITY", "GRID_PURCHASED");
        assertThat(jdbc.queryForObject(
                "SELECT config_revision FROM biz_energy_point_profile WHERE profile_id='MYSQL_PROFILE_OK'",
                Integer.class)).isZero();

        assertThatThrownBy(() -> insert(jdbc, "MYSQL_PROFILE_DUP", "POINT004", "BLD001",
                "ELECTRICITY", "GRID_PURCHASED")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(jdbc, "MYSQL_PROFILE_SUBTYPE", "POINT003", "BLD001",
                "NATURAL_GAS", "GRID_PURCHASED")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(jdbc, "MYSQL_PROFILE_BUILDING", "POINT020", "BLD001",
                "ELECTRICITY", "GRID_PURCHASED")).isInstanceOf(DataAccessException.class);
    }

    private static void insert(JdbcTemplate jdbc, String profileId, String pointId, String buildingId,
                               String energyType, String subtype) {
        jdbc.update("""
                INSERT INTO biz_energy_point_profile
                (profile_id,point_id,building_id,energy_type,energy_subtype,value_semantics,
                 reporting_period,annual_summary,confirmation_status,evidence_reference,
                 create_by,update_by)
                VALUES (?,?,?,?,?,'INSTANTANEOUS','MONTH',1,'CONFIRMED','隔离数据库约束测试',1,1)
                """, profileId, pointId, buildingId, energyType, subtype);
    }
}
