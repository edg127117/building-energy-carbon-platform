package com.platform.energy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.classification.ClassificationContracts.Reading;
import com.platform.energy.classification.ClassificationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 专用一次性 MySQL 8 验证完整迁移链、能源闭环约束及通用分类的持久化与并发。 */
@EnabledIfEnvironmentVariable(named = "ENERGY_LOOP7_MYSQL_IT_URL", matches = ".+")
class EnergyLoop7MysqlIntegrationTest {
    private static final List<String> LOOP_TABLES = List.of(
            "biz_energy_item_version",
            "biz_energy_conversion_parameter_version",
            "biz_energy_meter_event_version",
            "biz_energy_period_result_snapshot",
            "biz_energy_boundary_summary_policy_version");

    @Test
    void migratesEmptyDatabaseAndEnforcesLoopGovernance() throws Exception {
        String url = System.getenv("ENERGY_LOOP7_MYSQL_IT_URL");
        assertThat(System.getenv("ENERGY_LOOP7_MYSQL_IT_ISOLATED"))
                .as("集成测试必须由调用方显式确认使用一次性隔离 MySQL")
                .isEqualTo("true");
        assertThat(url).as("隔离集成测试固定使用项目数据库名").contains("/iot_platform");
        DataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("ENERGY_LOOP7_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("ENERGY_LOOP7_MYSQL_IT_PASSWORD", "test-root"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class)).as("隔离库必须为空").isZero();

        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init")
                .target("74").load().migrate();
        Integer equipmentBefore = jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment", Integer.class);
        Integer pointsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class);
        Flyway classification = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").target("75").load();
        assertThat(classification.migrate().migrationsExecuted).as("从既有V74升级只执行新增V75").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment", Integer.class)).isEqualTo(equipmentBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class)).isEqualTo(pointsBefore);

        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("75");
        var historyBeforeAccess = jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        Flyway access = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init")
                .target("76").load();
        assertThat(access.migrate().migrationsExecuted).as("V75到V76只登记运维职责目录").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_backend_duty WHERE duty_key='OPERATIONS_OBSERVER'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_backend_duty WHERE duty_key='OPERATIONS_OBSERVER'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history WHERE version<>'76' ORDER BY installed_rank"))
                .isEqualTo(historyBeforeAccess);
        Flyway configuration = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/env/init").target("83").load();
        assertThat(configuration.migrate().migrationsExecuted).as("V76到V83执行本轮配置迁移").isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("83");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_backend_duty WHERE duty_key='OPERATIONS_OBSERVER'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment", Integer.class)).isEqualTo(equipmentBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class)).isEqualTo(pointsBefore);
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").load();
        assertThat(flyway.migrate().migrationsExecuted).as("V83到V84只增加D01原始保全与冲突治理").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("84");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).as("重跑迁移不得重复建表或改写历史").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_equipment_type WHERE type_code='ODU'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT code_template FROM biz_point_naming_rule WHERE rule_id='RULE_ODU_MAIN'
                """, String.class)).isEqualTo("ODU[n]");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_protocol_draft
                WHERE draft_id='PROTOCOL_ODU_METER_339_V1'
                """, Integer.class)).isEqualTo(1);
        for(String table:List.of("biz_protocol_target","biz_protocol_version","biz_protocol_deployment","biz_protocol_migration_archive")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT path FROM sys_menu WHERE id=255",String.class))
                .isEqualTo("/configuration/ingestion/protocols");
        assertThat(jdbc.queryForList("SELECT r.role_key FROM sys_role_menu rm JOIN sys_role r ON r.id=rm.role_id WHERE rm.menu_id=255",String.class))
                .containsExactly("PLATFORM_ADMIN");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_product_point_template
                WHERE product_id='PRODUCT_IDU_METER_1039' AND for_calc=0
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                """, String.class)).containsAll(LOOP_TABLES).contains("biz_daikin_directory_sync_job");
        assertThat(jdbc.queryForList("""
                SELECT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE()
                  AND table_name IN ('biz_energy_period_result_snapshot',
                                     'biz_energy_boundary_summary_policy_version')
                """, String.class)).contains(
                        "idx_energy_period_snapshot_visible",
                        "idx_energy_boundary_summary_policy_effective");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO biz_energy_recalculation_batch
                  (batch_id,building_id,idempotency_key,request_hash,mode,status,reason,
                   total_items,submitted_by,submitted_at)
                VALUES ('LOOP7_BAD_COUNT','BLD001','loop7-bad-count',REPEAT('a',64),
                        'SAME_RULES','CREATED','隔离库约束验证',0,1,CURRENT_TIMESTAMP)
                """)).isInstanceOf(DataAccessException.class);

        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy
                  (policy_id,building_id,metering_boundary_id,energy_item_code,created_by)
                VALUES ('LOOP7_POLICY','BLD001','LOOP7_BOUNDARY','ELECTRICITY',1)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy_version
                  (version_id,policy_id,version_no,aggregation_mode,status,source_type,
                   evidence_reference,effective_from,created_by)
                VALUES ('LOOP7_POLICY_BAD','LOOP7_POLICY',1,'MAIN_METER_TOTAL',
                        'PENDING_EXPERT','MANUAL','隔离库约束验证','2026-01-01',1)
                """)).isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForList("""
                EXPLAIN SELECT snapshot_id FROM biz_energy_period_result_snapshot
                FORCE INDEX (idx_energy_period_snapshot_visible)
                WHERE building_id='BLD001' AND point_id='POINT004' AND period_type='MONTH'
                  AND period_start >= '2026-01-01' AND status <> 'SUPERSEDED'
                """)).singleElement().satisfies(row ->
                assertThat(row.get("key")).isEqualTo("idx_energy_period_snapshot_visible"));
        verifyClassificationStorage(dataSource, jdbc);
    }

    private void verifyClassificationStorage(DataSource dataSource, JdbcTemplate jdbc) throws Exception {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() AND engine='InnoDB'
                """, String.class)).contains("biz_energy_classification_scope", "biz_energy_classification_version",
                "biz_energy_manual_batch", "biz_energy_manual_reading");
        assertThat(jdbc.queryForList("""
                SELECT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name IN
                ('biz_energy_classification_version','biz_energy_manual_batch','biz_energy_manual_reading')
                """, String.class)).contains("uk_classification_version", "uk_energy_manual_batch",
                "idx_classification_effective", "idx_energy_manual_period");

        var repository = new ClassificationRepository(jdbc);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant from = Instant.parse("2026-08-01T00:00:00Z"), to = Instant.parse("2026-09-01T00:00:00Z");
        String configuration = "{\"source\":\"隔离数据库验证\"}";
        transactions.executeWithoutResult(status -> {
            repository.lock("BLD001");
            repository.insertVersion("MYSQL_CLASS_V1", "BLD001", configuration, "隔离测试", 1);
            var version = repository.version("BLD001", "MYSQL_CLASS_V1");
            assertThat(version.json()).isEqualTo(configuration);
            assertThat(repository.save(version, 0, configuration, "保存")).isEqualTo(1);
            assertThat(repository.save(version, 0, configuration, "过期修订")).isZero();
            assertThat(repository.submit(version, 1, "提交")).isEqualTo(1);
            assertThat(repository.approve(version, 2, from, 2, "独立审核")).isEqualTo(1);
        });
        assertThat(repository.effective("BLD001", from, to)).singleElement()
                .satisfies(version -> assertThat(version.reviewer()).isEqualTo(2L));
        assertThat(repository.version("OTHER_BUILDING", "MYSQL_CLASS_V1")).isNull();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            repository.lock("BLD001");
            repository.insertVersion("MYSQL_CLASS_ROLLBACK", "BLD001", configuration, "回滚验证", 1);
            var version = repository.version("BLD001", "MYSQL_CLASS_ROLLBACK");
            repository.submit(version, 0, "提交");
            repository.approve(version, 1, to, 2, "审核");
            throw new IllegalStateException("模拟审计写入失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.version("BLD001", "MYSQL_CLASS_ROLLBACK")).isNull();
        assertThat(repository.version("BLD001", "MYSQL_CLASS_V1").to()).isNull();

        BigDecimal exact = new BigDecimal("99999999999999999999999999.123456789012");
        var json = new ObjectMapper().findAndRegisterModules();
        Reading reading = new Reading("POINT004", from, to, "ELECTRICITY", "kWh", "PERIOD_TOTAL",
                null, null, exact, BigDecimal.ONE, true, "CONFIRMED", "隔离样例", "EXACT", null);
        repository.insertBatch("MYSQL_BATCH", "BLD001", "mysql-key", "a".repeat(64), "隔离来源", 1);
        repository.insertReading("MYSQL_FACT", "MYSQL_BATCH", "BLD001", reading, json.writeValueAsString(reading), exact, 1);
        assertThat(repository.readings("BLD001", from, to, true)).isEmpty();
        assertThat(repository.approveBatch("BLD001", "MYSQL_BATCH", 0, 2)).isEqualTo(1);
        assertThat(repository.approveBatch("BLD001", "MYSQL_BATCH", 0, 2)).isZero();
        assertThat(repository.readings("BLD001", from, to, true)).singleElement().satisfies(value -> {
            assertThat(value.quantity()).isEqualByComparingTo(exact);
            assertThat(value.batchStatus()).isEqualTo("APPROVED");
        });
        assertThat(repository.batch("OTHER_BUILDING", "MYSQL_BATCH", false)).isNull();
        assertThat(repository.readings("OTHER_BUILDING", from, to, true)).isEmpty();
        assertThatThrownBy(() -> repository.insertBatch("MYSQL_DUPLICATE", "BLD001", "mysql-key",
                "b".repeat(64), "重复幂等键", 1)).isInstanceOf(DataAccessException.class);

        Reading pending = new Reading("POINT004", from, to, "ELECTRICITY", "kWh", "PERIOD_TOTAL",
                null, null, BigDecimal.TEN, null, false, "PENDING_EXPERT", "倍率未知", "DATE_ONLY", null);
        repository.insertBatch("MYSQL_PENDING", "BLD001", "mysql-pending", "c".repeat(64), "保留未知倍率", 1);
        repository.insertReading("MYSQL_UNKNOWN", "MYSQL_PENDING", "BLD001", pending, json.writeValueAsString(pending), null, 1);
        assertThat(repository.batchReadings("BLD001", "MYSQL_PENDING")).singleElement()
                .satisfies(value -> assertThat(value.quantity()).isNull());

        BigDecimal corrected = new BigDecimal("0.010000000001");
        Reading revision = new Reading("POINT004", from, to, "ELECTRICITY", "kWh", "PERIOD_TOTAL",
                null, null, corrected, BigDecimal.ONE, true, "CONFIRMED", "明确修订", "EXACT", "MYSQL_FACT");
        repository.insertBatch("MYSQL_REVISION", "BLD001", "mysql-revision", "d".repeat(64), "修订", 1);
        repository.insertReading("MYSQL_FACT_V2", "MYSQL_REVISION", "BLD001", revision, json.writeValueAsString(revision), corrected, 1);
        assertThat(repository.readings("BLD001", from, to, true)).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo("MYSQL_FACT"));
        repository.approveBatch("BLD001", "MYSQL_REVISION", 0, 2);
        assertThat(repository.readings("BLD001", from, to, true)).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo("MYSQL_FACT_V2");
            assertThat(value.quantity()).isEqualByComparingTo(corrected);
        });
        assertThat(repository.batchReadings("BLD001", "MYSQL_BATCH")).hasSize(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) {
                final int index = i;
                tasks.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发起跑超时");
                    transactions.executeWithoutResult(status -> {
                        repository.lock("BLD001");
                        repository.insertVersion("MYSQL_CONCURRENT_" + index, "BLD001", configuration, "并发序号", 1);
                    });
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertThat(repository.versions("BLD001")).hasSize(13)
                .extracting(ClassificationRepository.Version::no).doesNotHaveDuplicates();
    }
}
