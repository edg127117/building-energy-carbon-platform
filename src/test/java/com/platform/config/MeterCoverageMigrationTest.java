package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 隔离 H2 执行迁移的结构和分类数据逻辑；不替代真实 MySQL 8 迁移验收。 */
class MeterCoverageMigrationTest {
    @Test
    void repairsOnlyMeterProductsAndKeepsExistingDeviceAndPointIdentity() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:meter-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE biz_equipment_type(type_code VARCHAR(20) PRIMARY KEY,type_name VARCHAR(100),asset_code_prefix VARCHAR(20),equip_category VARCHAR(50),standard_source VARCHAR(100),status INT)");
        jdbc.execute("CREATE TABLE biz_point_naming_rule(rule_id VARCHAR(32) PRIMARY KEY,standard_version VARCHAR(50),family_code VARCHAR(20),component_code VARCHAR(20),code_template VARCHAR(100),standard_source VARCHAR(100),status INT)");
        jdbc.execute("CREATE TABLE biz_device_product(product_id VARCHAR(32) PRIMARY KEY,equipment_type_code VARCHAR(20))");
        jdbc.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32) PRIMARY KEY,equip_code VARCHAR(50),equip_name VARCHAR(100),type_code VARCHAR(20),equip_category VARCHAR(50),product_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_data_point(point_id VARCHAR(32) PRIMARY KEY,point_code VARCHAR(100),equip_id VARCHAR(32))");
        jdbc.update("INSERT INTO biz_device_product VALUES('PRODUCT_IDU_METER_1039','IDU'),('PRODUCT_ODU_METER_339','ODU'),('REAL_INDOOR','IDU')");
        jdbc.update("INSERT INTO biz_equipment VALUES('m1','IDU1','单项电表样例','IDU','INDOOR_UNIT','PRODUCT_IDU_METER_1039'),('m2','ODU1','三项电表样例','ODU','OUTDOOR_UNIT','PRODUCT_ODU_METER_339'),('a1','IDU2','真实内机','IDU','INDOOR_UNIT','REAL_INDOOR')");
        jdbc.update("INSERT INTO biz_data_point VALUES('p1','IDU1_EPP','m1')");
        String sql=Files.readString(Path.of("src/env/init/V66__mysql_meter_coverage.sql"))
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", "");
        try(var connection=ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(jdbc.queryForObject("SELECT type_code FROM biz_equipment WHERE equip_id='m1'",String.class)).isEqualTo("ELECTRIC_METER_1P");
        assertThat(jdbc.queryForObject("SELECT type_code FROM biz_equipment WHERE equip_id='m2'",String.class)).isEqualTo("ELECTRIC_METER_3P");
        assertThat(jdbc.queryForObject("SELECT type_code FROM biz_equipment WHERE equip_id='a1'",String.class)).isEqualTo("IDU");
        assertThat(jdbc.queryForObject("SELECT equip_code FROM biz_equipment WHERE equip_id='m1'",String.class)).isEqualTo("IDU1");
        assertThat(jdbc.queryForObject("SELECT point_code FROM biz_data_point WHERE point_id='p1'",String.class)).isEqualTo("IDU1_EPP");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_meter_coverage_revision",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT equip_name FROM biz_equipment WHERE equip_id='m2'",String.class)).isEqualTo("三相电表样例");
    }
}
