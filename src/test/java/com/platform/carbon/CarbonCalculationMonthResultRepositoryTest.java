package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 隔离 H2 执行月结果精确 SQL；不替代 MySQL 或现场验收。 */
class CarbonCalculationMonthResultRepositoryTest {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private JdbcTemplate jdbc;
    private CarbonCalculationRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:carbon_month_"
                + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE biz_carbon_calculation_batch (
                  calculation_batch_id VARCHAR(32) PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
                  period_type VARCHAR(16) NOT NULL, period_start TIMESTAMP NOT NULL,
                  period_end TIMESTAMP NOT NULL, timezone_id VARCHAR(64) NOT NULL,
                  result_nature VARCHAR(32) NOT NULL, publication_status VARCHAR(20) NOT NULL,
                  status VARCHAR(32) NOT NULL, idempotency_key VARCHAR(100), request_hash VARCHAR(64),
                  active_lock_key VARCHAR(200), rounding_policy_version_id VARCHAR(32),
                  supersedes_calculation_batch_id VARCHAR(32), started_at TIMESTAMP, deadline_at TIMESTAMP,
                  completed_at TIMESTAMP, duration_ms BIGINT, snapshot_count INT DEFAULT 0,
                  detail_count INT DEFAULT 0, slow_calculation BOOLEAN DEFAULT FALSE,
                  safe_error_code VARCHAR(100), safe_error_message VARCHAR(500), created_by BIGINT,
                  created_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE biz_carbon_result_relation (
                  relation_id VARCHAR(32) PRIMARY KEY, old_calculation_batch_id VARCHAR(32) NOT NULL,
                  new_calculation_batch_id VARCHAR(32) NOT NULL, relation_status VARCHAR(20) NOT NULL
                )
                """);
        repository = new CarbonCalculationRepository(jdbc);
    }

    @Test
    void findsExactCrossYearMonthsBeyondTheHundredRowHistoryLimit() {
        Instant october2025 = start("2025-10");
        Instant october2026 = start("2026-10");
        insert("TARGET_2025", "BLD001", october2025, end("2025-10"), "Asia/Shanghai",
                "FORMAL", "DIRECT", "COMPLETED_COMPLETE", Instant.parse("2000-01-01T00:00:00Z"));
        insert("TARGET_2026", "BLD001", october2026, end("2026-10"), "Asia/Shanghai",
                "FORMAL", "PUBLISHED", "COMPLETED_COMPLETE", Instant.parse("2000-01-01T00:00:00Z"));
        for (int i = 0; i <= 100; i++) {
            insert("HISTORY" + i, "BLD001", start("2024-01"), end("2024-01"), "Asia/Shanghai",
                    "FORMAL", "DIRECT", "COMPLETED_COMPLETE", Instant.parse("2026-12-01T00:00:00Z"));
        }

        assertThat(repository.findCurrentFormalMonth("BLD001", october2025, end("2025-10")))
                .extracting(CarbonModels.CalculationBatch::batchId).containsExactly("TARGET_2025");
        assertThat(repository.findCurrentFormalMonth("BLD001", october2026, end("2026-10")))
                .extracting(CarbonModels.CalculationBatch::batchId).containsExactly("TARGET_2026");
    }

    @Test
    void excludesSupersededAndCandidateResultsAndCapsConflictsAtTwo() {
        Instant start = start("2026-10");
        Instant end = end("2026-10");
        insert("SUPERSEDED", "BLD001", start, end, "Asia/Shanghai", "FORMAL", "PUBLISHED",
                "COMPLETED_COMPLETE", Instant.parse("2026-10-02T00:00:00Z"));
        insert("CANDIDATE", "BLD001", start, end, "Asia/Shanghai", "FORMAL", "DIRECT",
                "COMPLETED_COMPLETE", Instant.parse("2026-10-03T00:00:00Z"));
        insert("VALID_A", "BLD001", start, end, "Asia/Shanghai", "FORMAL", "DIRECT",
                "COMPLETED_COMPLETE", Instant.parse("2026-10-04T00:00:00Z"));
        jdbc.update("INSERT INTO biz_carbon_result_relation VALUES ('REL_SUP','SUPERSEDED','REPLACED','SUPERSEDED')");
        jdbc.update("INSERT INTO biz_carbon_result_relation VALUES ('REL_CAN','OLD_CAND','CANDIDATE','CANDIDATE')");

        assertThat(repository.findCurrentFormalMonth("BLD001", start, end))
                .extracting(CarbonModels.CalculationBatch::batchId).containsExactly("VALID_A");

        insert("VALID_B", "BLD001", start, end, "Asia/Shanghai", "FORMAL", "DIRECT",
                "COMPLETED_COMPLETE", Instant.parse("2026-10-05T00:00:00Z"));
        insert("VALID_C", "BLD001", start, end, "Asia/Shanghai", "FORMAL", "DIRECT",
                "COMPLETED_COMPLETE", Instant.parse("2026-10-06T00:00:00Z"));

        assertThat(repository.findCurrentFormalMonth("BLD001", start, end))
                .extracting(CarbonModels.CalculationBatch::batchId)
                .hasSize(2).allMatch(id -> id.startsWith("VALID"));
    }

    @Test
    void returnsMissingForAnotherBuildingOrNonExactMonthRange() {
        Instant start = start("2026-10");
        Instant end = end("2026-10");
        insert("FOREIGN", "BLD002", start, end, "Asia/Shanghai", "FORMAL", "DIRECT",
                "COMPLETED_COMPLETE", Instant.parse("2026-10-01T00:00:00Z"));
        insert("CROSS_RANGE", "BLD001", start, end.plusSeconds(1), "Asia/Shanghai", "FORMAL",
                "DIRECT", "COMPLETED_COMPLETE", Instant.parse("2026-10-01T00:00:00Z"));
        insert("WRONG_ZONE", "BLD001", start, end, "UTC", "FORMAL", "DIRECT",
                "COMPLETED_COMPLETE", Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(repository.findCurrentFormalMonth("BLD001", start, end)).isEmpty();
    }

    private void insert(String id, String buildingId, Instant start, Instant end, String timezone,
                        String nature, String publication, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO biz_carbon_calculation_batch
                (calculation_batch_id,building_id,period_type,period_start,period_end,timezone_id,
                 result_nature,publication_status,status,idempotency_key,request_hash,
                 rounding_policy_version_id,started_at,deadline_at,completed_at,created_by,created_at)
                VALUES (?,?, 'MONTH',?,?,?,?,?,?,?,?, 'ROUNDING',?,?,?,1,?)
                """, id, buildingId, Timestamp.from(start), Timestamp.from(end), timezone, nature,
                publication, status, "idem-" + id, "hash-" + id, Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private static Instant start(String month) {
        return YearMonth.parse(month).atDay(1).atStartOfDay(BEIJING).toInstant();
    }

    private static Instant end(String month) {
        return YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay(BEIJING).toInstant();
    }
}
