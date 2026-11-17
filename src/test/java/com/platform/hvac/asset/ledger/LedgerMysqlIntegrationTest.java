package com.platform.hvac.asset.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.communication.CommunicationModels;
import com.platform.iot.communication.CommunicationProperties;
import com.platform.iot.communication.DeviceCommunicationService;
import com.platform.iot.qualityusage.QualityUsageModels;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LatestRawReading;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 显式启用的一次性 MySQL 验证；TDengine 最近值使用替身，不连接任何运行建筑。 */
@EnabledIfEnvironmentVariable(named = "LEDGER_MYSQL_IT_ISOLATED", matches = "true")
class LedgerMysqlIntegrationTest {
    @Test
    void upgradesLatestSchemaAndReadsScopedLedgerWithoutChangingExistingRecords() {
        String url = System.getenv("LEDGER_MYSQL_IT_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iot_platform\\?.+");
        var dataSource = new DriverManagerDataSource(url, "ledger_it", System.getenv("LEDGER_MYSQL_IT_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init")
                .target("120").load().migrate();
        int buildings = jdbc.queryForObject("SELECT COUNT(*) FROM building", Integer.class);
        int points = jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class);
        var menus = jdbc.queryForList("SELECT id,parent_id,path FROM sys_menu ORDER BY id");
        var grants = jdbc.queryForList("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id");

        var flyway = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM building", Integer.class)).isEqualTo(buildings);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class)).isEqualTo(points);
        assertThat(jdbc.queryForList("SELECT id,parent_id,path FROM sys_menu ORDER BY id")).isEqualTo(menus);
        assertThat(jdbc.queryForList("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id")).isEqualTo(grants);

        jdbc.update("INSERT INTO building(building_id,building_name,building_code,building_type,total_gfa,climate_zone,region_code) VALUES('LEDGER_IT','台账隔离楼','LEDGER_IT','办公',1000,'夏热冬冷','320113')");
        jdbc.update("INSERT INTO biz_space(space_id,building_id,space_name,space_code,space_type) VALUES('LEDGER_IT_SPACE','LEDGER_IT','隔离设备间','IT_SPACE','ROOM')");
        jdbc.update("INSERT INTO biz_system_group(system_group_id,system_group_code,building_id,system_type,system_group_name) VALUES('LEDGER_IT_GROUP','LEDGER_IT_GROUP','LEDGER_IT','HVAC','隔离系统')");
        jdbc.update("""
                INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,building_id,system_group_id,space_id)
                VALUES('LEDGER_IT_METER','METER_IT','台账电表','ELECTRIC_METER_3P','ELECTRIC_METER','LEDGER_IT','LEDGER_IT_GROUP','LEDGER_IT_SPACE'),
                      ('LEDGER_IT_IDU','IDU_IT','台账内机','IDU','INDOOR_UNIT','LEDGER_IT','LEDGER_IT_GROUP','LEDGER_IT_SPACE')
                """);
        jdbc.update("""
                INSERT INTO biz_point_naming_rule(rule_id,standard_version,family_code,component_code,code_template,standard_source)
                VALUES('LEDGER_IT_RULE','LEDGER_IT','ELECTRIC_METER_3P','MAIN','METER[n]','LEDGER_IT')
                """);
        jdbc.update("""
                INSERT INTO biz_data_point(point_id,point_code,point_name,building_id,equip_id,
                  naming_rule_id,family_code,component_code,suffix_code,data_type,unit)
                VALUES('LEDGER_IT_POWER','LEDGER_IT_P','有功功率','LEDGER_IT','LEDGER_IT_METER',
                  'LEDGER_IT_RULE','ELECTRIC_METER_3P','MAIN','P','ANALOG','kW')
                """);
        jdbc.update("""
                INSERT INTO biz_device_identity(identity_id,identity_type,identity_value,equip_id,building_id,expected_profile_code)
                VALUES('LEDGER_IT_IDENTITY','MAC','LEDGER-IT-MAC','LEDGER_IT_METER','LEDGER_IT','ELECTRIC_METER_V1')
                """);
        long now = System.currentTimeMillis();
        var communication = new DeviceCommunicationService(jdbc, new DataSourceTransactionManager(dataSource),
                new CommunicationProperties(), Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), 60);
        assertThat(communication.observe(new CommunicationModels.Signal("LEDGER_IT_IDENTITY",
                "LEDGER_IT_METER", "LEDGER_IT", "MQTT", "IT-1", now, now,
                false, "LIVE_TELEMETRY", null, "ELECTRIC_METER_V1"))).isTrue();
        var raw = mock(HvacRawEventRepository.class);
        when(raw.findLatestByEquipmentPoints(eq("LEDGER_IT"), eq("LEDGER_IT_METER"), anyCollection()))
                .thenReturn(List.of(new LatestRawReading("LEDGER_IT_POWER", 0, now, now, 0)));
        var quality = mock(QualityUsagePolicyResolver.class);
        when(quality.resolve(any(), eq("LEDGER_IT_POWER"), eq(QualityUsageModels.POINT_REALTIME_VIEW),
                anyLong(), eq(0))).thenReturn(new QualityUsageModels.Resolution(QualityUsageModels.Decision.ALLOW,
                0, QualityUsageModels.POINT_REALTIME_VIEW, QualityUsageModels.PolicySource.SYSTEM_DEFAULT_Q0_ONLY,
                null, 1L, "QUALITY_ALLOWED"));
        var service = new LedgerService(jdbc, raw, quality, new ObjectMapper(), communication);
        var all = service.query(1, 20, "LEDGER_IT", null, null, null, null, "ALL", Set.of("PLATFORM_ADMIN"));
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.summary().online()).isEqualTo(1);
        assertThat(all.summary().notMonitored()).isEqualTo(1);
        var meter = all.items().stream().filter(i -> i.equipment().equipmentId().equals("LEDGER_IT_METER"))
                .findFirst().orElseThrow();
        assertThat(meter.operation().applicable()).isFalse();
        assertThat(meter.displayPoint().value()).isZero();
        assertThat(meter.displayPoint().status()).isEqualTo("AVAILABLE");
        assertThat(service.query(1, 1, "LEDGER_IT", null, null, null, null,
                "POWER", Set.of("PLATFORM_ADMIN")).total()).isEqualTo(1);
        communication.sourceHealth("MQTT", "FAILED", now);
        assertThat(service.query(1, 20, "LEDGER_IT", null, null, null, null,
                "ALL", Set.of("PLATFORM_ADMIN")).summary().communicationUnknown()).isEqualTo(1);
    }
}
