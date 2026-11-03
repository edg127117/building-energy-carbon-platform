package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.YearMonth;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** H2仅验证持久化任务、租约和来源头选择；实际MySQL查询性能另在隔离实例验收。 */
class DailyTrendSummaryRepositoryTest {
    JdbcTemplate jdbc;
    DailyTrendSummaryRepository repo;
    DriverManagerDataSource data;
    @BeforeEach void setup() throws Exception {
        data=new DriverManagerDataSource("jdbc:h2:mem:summary-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(data);repo=new DailyTrendSummaryRepository(jdbc,new DailyTrendSummaryProperties());
        String ddl=java.nio.file.Files.readString(java.nio.file.Path.of("src/env/init/V102__mysql_daily_trend_read_model.sql"));
        jdbc.execute(ddl.replaceAll("(?s)ENGINE=InnoDB.*?;",";"));
        jdbc.execute("CREATE TABLE biz_energy_classification_version(version_id VARCHAR,building_id VARCHAR,revision BIGINT,effective_from BIGINT,effective_to BIGINT,status VARCHAR)");
        jdbc.execute("CREATE TABLE biz_energy_manual_batch(batch_id VARCHAR,building_id VARCHAR,payload_hash VARCHAR,revision BIGINT,reviewed_at BIGINT,status VARCHAR)");
        jdbc.execute("CREATE TABLE biz_relation_version(version_id VARCHAR,building_id VARCHAR,config_revision BIGINT,status VARCHAR,snapshot_sha256 VARCHAR,effective_at TIMESTAMP,superseded_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_relation_model(model_id VARCHAR,building_id VARCHAR,governance_mode VARCHAR,active_version_id VARCHAR)");
        jdbc.execute("CREATE TABLE biz_historical_metering_scope(scope_id VARCHAR,building_id VARCHAR,relation_version_id VARCHAR,start_epoch_ms BIGINT,end_epoch_ms BIGINT,status VARCHAR)");
        jdbc.execute("CREATE TABLE biz_data_point(point_id VARCHAR,building_id VARCHAR,equip_id VARCHAR,del_flag INT)");
        jdbc.execute("CREATE TABLE biz_energy_period_result_snapshot(snapshot_id VARCHAR,building_id VARCHAR,snapshot_version INT,status VARCHAR,evidence_hash VARCHAR,source_batch_id VARCHAR,period_type VARCHAR,period_start TIMESTAMP,period_end TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_energy_recalculation_batch(batch_id VARCHAR,status VARCHAR)");
        jdbc.execute("CREATE TABLE biz_carbon_hourly_batch(batch_id VARCHAR,building_id VARCHAR,day_date DATE,revision INT,calculation_as_of BIGINT,view_hash VARCHAR,evidence_hash VARCHAR,created_by BIGINT)");
    }
    @Test void leaseRecoveryAndSupersedingSourcePreventOldPublication() {
        repo.request("B","2026-01",1,"v1",1000);var h=repo.head("B","2026-01");
        String old=repo.claim(h,1000);assertThat(repo.claim(h,1001)).isNull();
        String recovery=repo.claim(h,200000);assertThat(recovery).isNotNull();
        assertThat(repo.publish(h,old,"{}","hash",200001)).isFalse();
        assertThat(repo.publish(h,recovery,"{}","hash",200001)).isTrue();
        repo.request("B","2026-01",2,"v2",200010);
        assertThat(repo.head("B","2026-01").builtVersion()).isEqualTo("v1");
        assertThat(repo.head("B","2026-01").state()).isEqualTo("PENDING");
        assertThat(repo.publish(h,recovery,"old","hash",200011)).isFalse();
        var restarted=new DailyTrendSummaryRepository(jdbc,new DailyTrendSummaryProperties());
        assertThat(restarted.due(200020)).hasSize(1);
    }
    @Test void sourceHeadsDetectApprovalRevisionAndOnlyRelevantHourlyMonth() {
        var month=YearMonth.of(2026,1);String before=repo.sourceVersion("B",month);
        jdbc.update("INSERT INTO biz_energy_manual_batch VALUES ('M','B','immutable',1,1,'PENDING_REVIEW')");
        assertThat(repo.sourceVersion("B",month)).isEqualTo(before);
        jdbc.update("UPDATE biz_energy_manual_batch SET status='APPROVED'");
        String approved=repo.sourceVersion("B",month);assertThat(approved).isNotEqualTo(before);
        jdbc.update("INSERT INTO biz_carbon_hourly_batch VALUES ('H','B','2026-02-01',1,1,'v','e',1)");
        assertThat(repo.sourceVersion("B",month)).isEqualTo(approved);
        jdbc.update("INSERT INTO biz_carbon_hourly_batch VALUES ('H2','B','2026-01-01',1,1,'v','e',1)");
        assertThat(repo.sourceVersion("B",month)).isNotEqualTo(approved);
        assertThat(repo.sourceVersion("OTHER",month)).isNotEqualTo(before);
    }
    @Test void tasksRollbackWithTheirTransactionAndDiscoverOnlyExistingHourMonths() {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        tx.execute(s->{repo.request("B","2026-01",1,"v",1000);s.setRollbackOnly();return null;});
        assertThat(repo.head("B","2026-01")).isNull();
        jdbc.update("INSERT INTO biz_carbon_hourly_batch VALUES ('H','B','2026-01-01',1,1,'v','e',1)");
        assertThat(repo.discover()).containsExactly(new DailyTrendSummaryRepository.Candidate("B","2026-01",1));
        repo.request("B","2026-01",1,"v",1000);assertThat(repo.discover()).isEmpty();
    }
    @Test void visibleBatchStatusAndHistoricalRelationshipChangesInvalidateVersion() {
        var month=YearMonth.of(2026,1);String initial=repo.sourceVersion("B",month);
        jdbc.update("INSERT INTO biz_relation_version VALUES ('R','B',1,'EFFECTIVE','hash',CURRENT_TIMESTAMP,NULL)");
        String relation=repo.sourceVersion("B",month);assertThat(relation).isNotEqualTo(initial);
        jdbc.update("INSERT INTO biz_energy_recalculation_batch VALUES ('batch','CALCULATING')");
        jdbc.update("INSERT INTO biz_energy_period_result_snapshot VALUES ('S','B',1,'LOCKED_COMPLETE','hash','batch','DAY','2026-01-01 00:00:00','2026-01-02 00:00:00')");
        String pending=repo.sourceVersion("B",month);
        jdbc.update("UPDATE biz_energy_recalculation_batch SET status='COMPLETED'");
        assertThat(repo.sourceVersion("B",month)).isNotEqualTo(pending);
    }
    @Test void discoveryCannotOverwriteAnAlreadyPublishedVersion() {
        repo.request("B","2026-01",1,"current",1000);
        var h=repo.head("B","2026-01");String token=repo.claim(h,1000);
        assertThat(repo.publish(h,token,"{}","hash",1001)).isTrue();
        repo.registerDiscovered(new DailyTrendSummaryRepository.Candidate("B","2026-01",2),1002);
        assertThat(repo.head("B","2026-01").state()).isEqualTo("READY");
        assertThat(repo.head("B","2026-01").sourceVersion()).isEqualTo("current");
        assertThat(repo.head("B","2026-01").user()).isEqualTo(1);
    }
}
