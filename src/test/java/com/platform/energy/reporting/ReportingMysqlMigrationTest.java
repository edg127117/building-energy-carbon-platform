package com.platform.energy.reporting;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 一次性空库先迁移V85再升级V86/V87，验证历史校验和、现有资产和职责不被重写。 */
@EnabledIfEnvironmentVariable(named="REPORTING_MYSQL_ISOLATED",matches="true")
class ReportingMysqlMigrationTest {
    @Test void reportingStorageKeepsTransactionsAndUnicodeUnderLegacyDefaults() throws Exception {
        String url=System.getenv("REPORTING_MYSQL_URL");assertThat(url).startsWith("jdbc:mysql://127.0.0.1:39487/iot_platform");
        var ds=new DriverManagerDataSource(url,System.getenv("REPORTING_MYSQL_USER"),System.getenv("REPORTING_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);String schema="reporting_storage_"+java.util.UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+schema+" CHARACTER SET latin1 COLLATE latin1_swedish_ci");
        try(var connection=ds.getConnection();var statement=connection.createStatement()) {
            statement.execute("USE "+schema);statement.execute("SET SESSION default_storage_engine=MyISAM");
            for(String sql:java.nio.file.Files.readString(java.nio.file.Path.of("src/env/init/V86__mysql_energy_reporting.sql")).split(";"))
                if(!sql.isBlank())statement.execute(sql);
            int tables=0;
            try(var result=statement.executeQuery("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                while(result.next()){tables++;assertThat(result.getString(1)).isEqualTo("InnoDB");assertThat(result.getString(2)).startsWith("utf8mb4_");}
            }
            assertThat(tables).isEqualTo(5);connection.setAutoCommit(false);
            String payload="{\"evidence\":\"中文凭证🌱\"}";
            try(var insert=connection.prepareStatement("INSERT INTO biz_energy_reporting_receipt VALUES ('B','request','hash',?)")) {
                insert.setString(1,payload);assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try(var result=statement.executeQuery("SELECT response_json FROM biz_energy_reporting_receipt")) {
                assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo(payload);
            }
            connection.rollback();
            try(var result=statement.executeQuery("SELECT COUNT(*) FROM biz_energy_reporting_receipt")) {
                assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
            }
        } finally {jdbc.execute("DROP DATABASE "+schema);}
    }
    @Test void upgradesWithoutChangingExistingHistoryAssetsOrDuties() {
        String url=System.getenv("REPORTING_MYSQL_URL");assertThat(url).startsWith("jdbc:mysql://127.0.0.1:39487/iot_platform");
        var ds=new DriverManagerDataSource(url,System.getenv("REPORTING_MYSQL_USER"),System.getenv("REPORTING_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(ds).locations("filesystem:src/env/init").target("85").load().migrate();
        var history=jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        var assets=jdbc.queryForList("SELECT equip_id FROM biz_equipment ORDER BY equip_id");
        var duties=jdbc.queryForList("SELECT * FROM sys_user_backend_duty ORDER BY assignment_id");
        var flyway=Flyway.configure().dataSource(ds).locations("filesystem:src/env/init").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);flyway.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history WHERE version NOT IN ('86','87') ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT equip_id FROM biz_equipment ORDER BY equip_id")).isEqualTo(assets);
        assertThat(jdbc.queryForList("SELECT * FROM sys_user_backend_duty ORDER BY assignment_id")).isEqualTo(duties);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_formula_version WHERE formula_version_id='CFV_HEAT_CO2_V1' AND result_basis='GAS_MASS' AND gas_coverage='CO2_ONLY_HEAT'",Integer.class)).isEqualTo(1);
        for(var table:List.of("biz_energy_reporting_scope","biz_energy_reporting_record","biz_energy_reporting_receipt"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThatThrownBy(()->jdbc.update("INSERT INTO biz_energy_reporting_record(record_id,building_id,record_kind,status,payload_json) VALUES ('bad','B','SOURCE','DRAFT','not-json')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
