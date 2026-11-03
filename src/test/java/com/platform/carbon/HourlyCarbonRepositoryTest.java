package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.platform.audit.AuditEnvironmentMode;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 隔离 H2 执行真实 V92，并验证小时结果的幂等、修订、完整性与审计原子性。 */
class HourlyCarbonRepositoryTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);
    private static final String EVIDENCE = "{\"source\":\"hourly-isolated-test\",\"exact\":0.123456789012345678901234567890123456789}";
    private static final String CANONICAL_EVIDENCE = CarbonEvidence.canonical(CarbonEvidence.parse(EVIDENCE));

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ObjectMapper mapper;
    private AuditGovernanceProperties auditProperties;
    private List<AuditEvidence> audits;
    private HourlyCarbonRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:hourly_carbon_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE biz_energy_manual_batch (batch_id VARCHAR(32) PRIMARY KEY)");
        jdbc.update("INSERT INTO biz_energy_manual_batch(batch_id) VALUES ('legacy-reviewed-batch')");
        try (Connection connection = dataSource.getConnection()) {
            String script = Files.readString(Path.of("src/env/init/V97__mysql_hourly_carbon.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
        }
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        auditProperties = new AuditGovernanceProperties();
        auditProperties.setEnvironmentMode(AuditEnvironmentMode.TEST);
        audits = new ArrayList<>();
        repository = new HourlyCarbonRepository(jdbc, mapper, audits::add, auditProperties);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void executesActualV92AndDoesNotBackfillLegacyApprovalTime() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE LOWER("TABLE_NAME")='biz_energy_manual_batch' AND LOWER("COLUMN_NAME")='reviewed_at'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reviewed_at FROM biz_energy_manual_batch WHERE batch_id='legacy-reviewed-batch'",
                Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE LOWER(\"TABLE_NAME\") IN ('biz_carbon_hourly_scope','biz_carbon_hourly_batch')", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void replaysSameIdempotencyAndRejectsDifferentRequestHash() {
        Instant asOf = Instant.parse("2026-10-26T12:00:00Z");
        HourlyCarbonRepository.Stored first = publish("BLD001", DAY, asOf, "hourly-once", hash("first"));
        HourlyCarbonRepository.Stored replay = publish("BLD001", DAY, asOf, "hourly-once", hash("first"));
        HourlyCarbonRepository.Stored explicitReplay = repository.replay("BLD001", "hourly-once", hash("first"));

        assertThat(replay.view().calculationBatchId()).isEqualTo(first.view().calculationBatchId());
        assertThat(explicitReplay.view().calculationBatchId()).isEqualTo(first.view().calculationBatchId());
        assertThat(replay.view().revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_hourly_batch", Integer.class)).isEqualTo(1);
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().sourceModule()).isEqualTo("CARBON_MANAGEMENT");
        assertThat(audits.getFirst().actionType()).isEqualTo("CREATE_HOURLY_CARBON_BATCH");

        assertThatThrownBy(() -> publish("BLD001", DAY, asOf, "hourly-once", hash("different")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo("HOURLY_CARBON_IDEMPOTENCY_CONFLICT");
                });
        assertThatThrownBy(() -> repository.replay("BLD001", "hourly-once", hash("different")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo("HOURLY_CARBON_IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    void storesEvidenceWithCarbonEvidenceCanonicalDecimalText() {
        HourlyCarbonRepository.Stored stored = publish("BLD001", DAY,
                Instant.parse("2026-10-26T12:00:00Z"), "precise-evidence", hash("precise"));

        assertThat(stored.evidenceJson()).isEqualTo(CANONICAL_EVIDENCE)
                .contains("\"exact\":\"0.123456789012345678901234567890123456789\"");
        assertThat(jdbc.queryForObject("SELECT evidence_json FROM biz_carbon_hourly_batch WHERE batch_id=?",
                String.class, stored.view().calculationBatchId())).isEqualTo(CANONICAL_EVIDENCE);
        var read=repository.byId(stored.view().calculationBatchId());
        assertThat(read.evidenceTree()).isEqualTo(CarbonEvidence.parse(CANONICAL_EVIDENCE));
        assertThat(read.evidenceJson()).isEqualTo(CANONICAL_EVIDENCE);
    }

    @Test
    void preservesOldRevisionAndKeepsNewestCalculationAsOfWhenLaterRevisionIsOlder() {
        Instant newestAsOf = Instant.parse("2026-10-26T12:00:00Z");
        HourlyCarbonRepository.Stored newest = publish("BLD001", DAY, newestAsOf, "hourly-newest", hash("newest"));
        HourlyCarbonRepository.Stored olderRevision = publish("BLD001", DAY,
                Instant.parse("2026-10-26T08:00:00Z"), "hourly-older", hash("older"));

        assertThat(olderRevision.view().revision()).isEqualTo(2);
        assertThat(repository.byDate("BLD001", DAY, 1).view().calculationBatchId())
                .isEqualTo(newest.view().calculationBatchId());
        assertThat(repository.byDate("BLD001", DAY, null).view().calculationBatchId())
                .isEqualTo(newest.view().calculationBatchId());
        assertThat(repository.byDate("BLD001", DAY, 2).view().calculationAsOf())
                .isEqualTo(Instant.parse("2026-10-26T08:00:00Z"));
    }

    @Test
    void keepsBuildingQueriesIsolated() {
        Instant asOf = Instant.parse("2026-10-26T12:00:00Z");
        HourlyCarbonRepository.Stored first = publish("BLD001", DAY, asOf, "shared-key", hash("bld-1"));
        HourlyCarbonRepository.Stored second = publish("BLD002", DAY, asOf, "shared-key", hash("bld-2"));

        assertThat(repository.byKey("BLD001", "shared-key").view().calculationBatchId())
                .isEqualTo(first.view().calculationBatchId());
        assertThat(repository.byDate("BLD002", DAY, null).view().calculationBatchId())
                .isEqualTo(second.view().calculationBatchId());
        assertThat(repository.byKey("BLD003", "shared-key")).isNull();
        assertThat(repository.byId("missing")).isNull();
    }

    @Test
    void rejectsTamperedViewOrEvidenceInsteadOfReturningIt() {
        Instant asOf = Instant.parse("2026-10-26T12:00:00Z");
        HourlyCarbonRepository.Stored viewTampered = publish("BLD001", DAY, asOf, "view-hash", hash("view"));
        jdbc.update("UPDATE biz_carbon_hourly_batch SET view_hash=? WHERE batch_id=?", "0".repeat(64),
                viewTampered.view().calculationBatchId());
        assertIntegrityFailure(viewTampered.view().calculationBatchId());

        HourlyCarbonRepository.Stored evidenceTampered = publish("BLD001", DAY, asOf, "evidence-hash", hash("evidence"));
        jdbc.update("UPDATE biz_carbon_hourly_batch SET evidence_json=? WHERE batch_id=?", "{\"source\":\"tampered\"}",
                evidenceTampered.view().calculationBatchId());
        assertIntegrityFailure(evidenceTampered.view().calculationBatchId());
    }

    @Test
    void rollsBackBatchAndScopeWhenAuditAppendFails() {
        HourlyCarbonRepository failing = new HourlyCarbonRepository(jdbc, mapper,
                evidence -> { throw new IllegalStateException("audit unavailable"); }, auditProperties);
        Instant asOf = Instant.parse("2026-10-26T12:00:00Z");
        HourlyCarbonContracts.Request request = request("BLD001", DAY, asOf, "audit-failure");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> failing.publish(7L, request,
                hash("audit-failure"), draft("BLD001", DAY, asOf), EVIDENCE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_hourly_batch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_hourly_scope", Integer.class)).isZero();
    }

    @Test void rejectsCommitWhenTheRequestDeadlineExpiresAfterPublish() {
        var deadline=CarbonCalculationDeadline.start(java.time.Duration.ofMillis(100));
        Instant asOf=Instant.parse("2026-10-26T12:00:00Z");
        try(var ignored=deadline.bind()) {
            assertThatThrownBy(()->transactions.executeWithoutResult(status->{
                repository.publish(7L,request("BLD001",DAY,asOf,"deadline"),hash("deadline"),draft("BLD001",DAY,asOf),EVIDENCE);
                while(!deadline.expired())Thread.onSpinWait();
            })).isInstanceOf(BusinessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_hourly_batch",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_hourly_scope",Integer.class)).isZero();
    }

    private HourlyCarbonRepository.Stored publish(String building, LocalDate date, Instant asOf,
                                                   String idempotencyKey, String requestHash) {
        return transactions.execute(status -> repository.publish(7L, request(building, date, asOf, idempotencyKey),
                requestHash, draft(building, date, asOf), EVIDENCE));
    }

    private static HourlyCarbonContracts.Request request(String building, LocalDate date, Instant asOf,
                                                          String idempotencyKey) {
        return new HourlyCarbonContracts.Request(building, date, asOf, idempotencyKey,
                "RAW_CUMULATIVE", "SOURCE_LABELLED", "isolated-hourly-test", "FORMAL");
    }

    private static HourlyCarbonContracts.View draft(String building, LocalDate date, Instant asOf) {
        return new HourlyCarbonContracts.View(building, date, "Asia/Shanghai", "KG_CO2E",
                "PURCHASED_ELECTRICITY_SCOPE_2", "RAW_CUMULATIVE", "SOURCE_LABELLED", "FORMAL", "MONITORING", "PARTIAL",
                null, null, asOf, asOf, "isolated test watermark", 24, 0, BigDecimal.ZERO, List.of(), asOf,
                CarbonCalculationCore.sha256(CANONICAL_EVIDENCE));
    }

    private static String hash(String value) {
        return CarbonCalculationCore.sha256(value);
    }

    private void assertIntegrityFailure(String batchId) {
        assertThatThrownBy(() -> repository.byId(batchId))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getErrorCode()).isEqualTo("HOURLY_CARBON_INTEGRITY_VIOLATION");
                });
    }
}
