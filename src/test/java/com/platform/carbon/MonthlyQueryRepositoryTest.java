package com.platform.carbon;

import com.platform.energy.reporting.ReportingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** H2检查查询选择语义；MySQL大证据排序故障另在独立服务器用原数据复验。 */
class MonthlyQueryRepositoryTest {
    private static final Instant START = Instant.parse("2026-08-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-09-30T16:00:00Z");
    private JdbcTemplate jdbc;
    private CarbonCalculationRepository repository;

    @BeforeEach void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:monthly_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE biz_carbon_calculation_batch(
                    calculation_batch_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),period_type VARCHAR(16),
                    period_start TIMESTAMP,period_end TIMESTAMP,timezone_id VARCHAR(64),result_nature VARCHAR(32),
                    publication_status VARCHAR(20),status VARCHAR(32),idempotency_key VARCHAR(100),request_hash VARCHAR(64),
                    active_lock_key VARCHAR(200),rounding_policy_version_id VARCHAR(32),supersedes_calculation_batch_id VARCHAR(32),
                    started_at TIMESTAMP,deadline_at TIMESTAMP,completed_at TIMESTAMP,duration_ms BIGINT,snapshot_count INT,
                    detail_count INT,slow_calculation BOOLEAN,safe_error_code VARCHAR(100),safe_error_message VARCHAR(500),
                    created_by BIGINT,created_at TIMESTAMP,shared_evidence_json CLOB)
                """);
        jdbc.execute("CREATE TABLE biz_carbon_result_relation(old_calculation_batch_id VARCHAR(32),new_calculation_batch_id VARCHAR(32),relation_status VARCHAR(20))");
        repository = new CarbonCalculationRepository(jdbc);
    }

    @Test void selectsPublishedCompleteNonSupersededMonthBeyondTheRecentHundredBatches() {
        batch("old", "DIRECT", "COMPLETED_COMPLETE", "Asia/Shanghai", 0);
        batch("published", "PUBLISHED", "COMPLETED_COMPLETE", "Asia/Shanghai", 1);
        jdbc.update("INSERT INTO biz_carbon_result_relation VALUES ('old','published','SUPERSEDED')");
        batch("candidate", "PUBLISHED", "COMPLETED_COMPLETE", "Asia/Shanghai", 2);
        jdbc.update("INSERT INTO biz_carbon_result_relation VALUES ('published','candidate','CANDIDATE')");
        batch("incomplete", "DIRECT", "COMPLETED_INCOMPLETE", "Asia/Shanghai", 3);
        batch("wrong-zone", "DIRECT", "COMPLETED_COMPLETE", "Asia/Manila", 4);
        for (int i = 0; i < 110; i++) {
            batch("later-" + i, "DIRECT", "COMPLETED_COMPLETE", "Asia/Shanghai", 10 + i);
            jdbc.update("UPDATE biz_carbon_calculation_batch SET period_start=?,period_end=? WHERE calculation_batch_id=?",
                    Timestamp.from(END), Timestamp.from(END.plusSeconds(86400)), "later-" + i);
        }
        assertThat(repository.listBatches("building")).hasSize(100)
                .noneMatch(v -> "published".equals(v.batchId()));
        assertThat(repository.findCurrentFormal("building", CarbonModels.PeriodType.MONTH, START, END, "Asia/Shanghai").batchId())
                .isEqualTo("published");
        assertThat(repository.findCurrentFormal("building", CarbonModels.PeriodType.YEAR, START, END)).isNull();
    }

    @Test void metadataListRetainsItsContractAndUsesDeterministicOrderingWithLargeSharedEvidence() {
        batch("a", "DIRECT", "COMPLETED_COMPLETE", "Asia/Shanghai", 0);
        batch("b", "DIRECT", "COMPLETED_COMPLETE", "Asia/Shanghai", 0);
        jdbc.update("UPDATE biz_carbon_calculation_batch SET shared_evidence_json=?", "large-frozen-evidence".repeat(50000));
        assertThat(repository.listBatches("building")).extracting(CarbonModels.CalculationBatch::batchId).containsExactly("b", "a");
        assertThat(repository.findBatch("a").buildingId()).isEqualTo("building");
    }

    @Test void sealedStatisticsQueryIgnoresLongHistoryAndRetiredBatchesAndBoundsConflictResults() {
        jdbc.execute("CREATE TABLE biz_energy_reporting_record(record_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),record_kind VARCHAR(20),status VARCHAR(20),revision BIGINT,month_date VARCHAR(10),payload_json CLOB)");
        for (int i = 0; i < 510; i++) reporting("history-" + i, "SEALED", "2025-01-01");
        reporting("retired", "SUPERSEDED", "2026-09-01");
        reporting("current", "SEALED", "2026-09-01");
        var stats = new ReportingRepository(jdbc);
        assertThat(stats.sealedMonth("building", LocalDate.of(2026, 9, 1))).singleElement()
                .extracting(ReportingRepository.Stored::id).isEqualTo("current");
        reporting("conflict-1", "SEALED", "2026-09-01");
        reporting("conflict-2", "SEALED", "2026-09-01");
        assertThat(stats.sealedMonth("building", LocalDate.of(2026, 9, 1))).hasSize(2);
    }

    private void batch(String id, String publication, String status, String timezone, int sequence) {
        jdbc.update("""
                INSERT INTO biz_carbon_calculation_batch
                (calculation_batch_id,building_id,period_type,period_start,period_end,timezone_id,result_nature,
                 publication_status,status,idempotency_key,rounding_policy_version_id,created_at,completed_at,started_at,deadline_at)
                VALUES (?,'building','MONTH',?,?,?,'FORMAL',?,?,?,'rounding',?,?,?,?)
                """, id, Timestamp.from(START), Timestamp.from(END), timezone, publication, status, id,
                Timestamp.from(END.plusSeconds(sequence)), Timestamp.from(END.plusSeconds(sequence)), Timestamp.from(END), Timestamp.from(END.plusSeconds(60)));
    }
    private void reporting(String id, String status, String month) {
        jdbc.update("INSERT INTO biz_energy_reporting_record VALUES (?,'building','BATCH',?,1,?,'{}')", id, status, month);
    }
}
