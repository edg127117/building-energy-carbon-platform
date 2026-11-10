package com.platform.carbon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.platform.carbon.DynamicElectricityRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real source SQL and stored Result validation, without a calculation or an external database. */
class DynamicElectricityQueryDayCacheTest extends QueryDayCacheFixture {
    @Test void wholeViewMatchesOldPathByteForByteFor219PointsAcross24Hours() {
        seedDay(219, 24);
        saveHours(219, 24);
        clearInvocations(repository, authorization, reader, live);

        var original = service.query(USER, ROLES, BUILDING, "DAY", PERIOD);

        assertThat(original.points()).hasSize(24).allSatisfy(point -> {
            assertThat(point.status()).isEqualTo("COMPLETE");
            assertThat(point.grains()).containsExactly("HOUR");
            assertThat(point.resultIds()).hasSize(1);
        });
        verify(repository, times(24)).electricityVersion(eq(BUILDING), any(), any(), eq(REVIEWED));
        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
        clearInvocations(repository);

        var optimized = readOnlyRepeatableRead().execute(status ->
                service.query(USER, ROLES, BUILDING, "DAY", PERIOD));

        assertThat(canonicalBytes(optimized)).isEqualTo(canonicalBytes(original));
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
        verify(repository, times(24)).factors(eq(BUILDING), any(), any());
        verifyNoInteractions(reader);
    }

    @Test void oversizedOptionalDayUsesAllOriginalVersionsWithoutFalseMissingResults() {
        seedDay(3, 24);
        jdbc.update("UPDATE biz_energy_manual_reading SET reading_json=? WHERE fact_id=?",
                "{\"large\":\"" + "x".repeat(16385) + "\"}", factId(0, 0));
        saveHours(3, 24);
        var original = service.query(USER, ROLES, BUILDING, "DAY", PERIOD);
        clearInvocations(repository);

        var optimized = readOnlyRepeatableRead().execute(status ->
                service.query(USER, ROLES, BUILDING, "DAY", PERIOD));

        assertThat(canonicalBytes(optimized)).isEqualTo(canonicalBytes(original));
        assertThat(optimized.points()).allMatch(point -> "COMPLETE".equals(point.status()));
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, times(24)).electricityVersion(eq(BUILDING), any(), any(), eq(REVIEWED));
    }

    @Test void rowCapacityFallbackDoesNotUseThePartialDayAlreadyRead() {
        seedDay(3, 24);
        saveHours(3, 24);
        var original = service.query(USER, ROLES, BUILDING, "DAY", PERIOD);
        var tiny = new CurrentReviewedDayVersionRead.Limits(2, 2, 1000, 3, 100_000, 16384);
        doAnswer(call -> CurrentReviewedDayVersionRead.read(repository, call.getArgument(0),
                call.getArgument(1), call.getArgument(2), call.getArgument(3), tiny))
                .when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
        clearInvocations(repository);

        var optimized = readOnlyRepeatableRead().execute(status ->
                service.query(USER, ROLES, BUILDING, "DAY", PERIOD));

        assertThat(canonicalBytes(optimized)).isEqualTo(canonicalBytes(original));
        assertThat(optimized.points()).allMatch(point -> "COMPLETE".equals(point.status()));
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, times(24)).electricityVersion(eq(BUILDING), any(), any(), eq(REVIEWED));
    }

    @Test void concurrentApprovalCannotMixOneRequestButTheNextRequestSeesIt() throws Exception {
        seedDay(3, 24);
        batch("pending", "DRAFT", 2);
        fact("pending-last-hour", "pending", "P-new", FROM.plusSeconds(23 * 3600L), DAY_END, "{}", null);
        saveHours(3, 24);
        var original = service.query(USER, ROLES, BUILDING, "DAY", PERIOD);
        var approved = new AtomicBoolean();
        try (var writer = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                var snapshot = call.callRealMethod();
                if (approved.compareAndSet(false, true)) {
                    writer.submit(() -> new JdbcTemplate(dataSource).update(
                            "UPDATE biz_energy_manual_batch SET status='APPROVED',reviewed_at=99 WHERE batch_id='pending'"))
                            .get(5, TimeUnit.SECONDS);
                }
                return snapshot;
            }).when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
            clearInvocations(repository);

            var duringApproval = readOnlyRepeatableRead().execute(status ->
                    service.query(USER, ROLES, BUILDING, "DAY", PERIOD));

            assertThat(approved).isTrue();
            assertThat(canonicalBytes(duringApproval)).isEqualTo(canonicalBytes(original));
            verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
            verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());

            var next = readOnlyRepeatableRead().execute(status ->
                    service.query(USER, ROLES, BUILDING, "DAY", PERIOD));
            assertThat(next.points().subList(0, 23)).isEqualTo(original.points().subList(0, 23));
            assertThat(next.points().getLast().status()).isEqualTo("STALE");
            assertThat(canonicalBytes(next)).isEqualTo(canonicalBytes(
                    service.query(USER, ROLES, BUILDING, "DAY", PERIOD)));
        }
    }

    @Test void authorizationRejectionPrecedesResultsMetadataAndSourceLoading() {
        doThrow(new BusinessException(403, "DENIED", "reader denied"))
                .when(authorization).requireReader(9, ROLES, BUILDING);
        clearInvocations(repository, reader, live);

        assertThatThrownBy(() -> readOnlyRepeatableRead().execute(status ->
                service.query(9, ROLES, BUILDING, "DAY", PERIOD)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reader denied");

        verifyNoInteractions(repository, reader, live);
    }

    @Test void payloadHashReferenceChangeIsRejectedBeforeAnyDayCacheLoads() {
        seedDay(1, 2);
        var results = saveHours(1, 2);
        jdbc.update("UPDATE biz_dynamic_electricity_result SET payload_hash=? WHERE result_id=?",
                hash("not-the-payload"), results.getFirst().id());
        clearInvocations(repository);

        assertThatThrownBy(() -> readOnlyRepeatableRead().execute(status ->
                service.query(USER, ROLES, BUILDING, "DAY", PERIOD)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("证据引用不一致");

        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
    }

    @Test void fullEvidenceFieldChangesAreDetectedByScheduledIntegrityAudit() {
        seedDay(1, 2);
        var results = saveHours(1, 2);
        var tree = (ObjectNode) CarbonEvidence.tree(results.getFirst());
        tree.putObject("capturedAt").put("invalid", true);
        String payload = CarbonEvidence.canonical(tree);
        jdbc.update("UPDATE biz_dynamic_electricity_result SET payload_json=?,payload_hash=? WHERE result_id=?",
                payload, hash(payload), results.getFirst().id());
        clearInvocations(repository);

        jdbc.update("UPDATE biz_dynamic_electricity_chart_projection SET last_verified_at=0 WHERE result_id=?",
                results.getFirst().id());
        assertThat(repository.verifyChartProjectionIntegrity(16)).isEqualTo(new DynamicElectricityRepository.IntegrityAudit(1,1));
        assertThatThrownBy(() -> readOnlyRepeatableRead().execute(status ->
                service.query(USER, ROLES, BUILDING, "DAY", PERIOD)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("完整性巡检未通过");

        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
    }

    @Test void sourceSqlFailurePropagatesWithoutRetryOrDegradedViewAndNextRequestStartsFresh() {
        seedDay(1, 2);
        saveHours(1, 2);
        var failure = new QueryTimeoutException("current-source-sql-timeout");
        doThrow(failure).when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
        clearInvocations(repository);

        assertThatThrownBy(() -> readOnlyRepeatableRead().execute(status ->
                service.query(USER, ROLES, BUILDING, "DAY", PERIOD))).isSameAs(failure);

        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
        doCallRealMethod().when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
        var next = readOnlyRepeatableRead().execute(status ->
                service.query(USER, ROLES, BUILDING, "DAY", PERIOD));
        assertThat(next.points().subList(0, 2)).allMatch(point -> "COMPLETE".equals(point.status()));
    }

    @Test void queryAndRawJdbcShutdownAreSafeWhenJdbcDebugLoggingIsEnabled() throws Exception {
        seedDay(1, 2);
        saveHours(1, 2);
        var logger = (Logger) LoggerFactory.getLogger(JdbcTemplate.class);
        Level previous = logger.getLevel();
        try {
            logger.setLevel(Level.DEBUG);
            var result = readOnlyRepeatableRead().execute(status ->
                    service.query(USER, ROLES, BUILDING, "DAY", PERIOD));
            assertThat(result.points().subList(0, 2)).allMatch(point -> "COMPLETE".equals(point.status()));
            shutdownDatabase();
        } finally {
            logger.setLevel(previous);
        }
    }

    private static byte[] canonicalBytes(Object value) {
        return encode(value).getBytes(StandardCharsets.UTF_8);
    }
}

/** Shared isolated SQL fixture for the request cache and its service integration tests. */
abstract class QueryDayCacheFixture {
    static final long USER = 7;
    static final String BUILDING = "B";
    static final String REVIEWED = "REVIEWED_INTERVAL";
    static final String PERIOD = "2025-02-01";
    static final Instant FROM = Instant.parse("2025-01-31T16:00:00Z");
    static final Instant DAY_END = FROM.plusSeconds(86400);
    static final Set<String> ROLES = Set.of("ENERGY_MANAGER", "CARBON_READER");
    static final String METADATA = "fixed-query-metadata";
    // Freeze the old six-field query independently of both new SQL phases and the cache.
    private static final String ORIGINAL_SQL = """
            SELECT /*+ INDEX(r idx_energy_manual_point_period) NO_SEMIJOIN(@dynamic_manual_replacement) */
                   r.fact_id,r.point_id,r.reading_json,r.normalized_quantity,b.revision,b.reviewed_at
            FROM biz_energy_manual_reading r JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
            WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>? AND b.status='APPROVED'
              AND NOT EXISTS (
                SELECT /*+ QB_NAME(dynamic_manual_replacement) JOIN_ORDER(s,sb) */ 1 FROM biz_energy_manual_reading s
                JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                WHERE s.supersedes_fact_id IS NOT NULL AND s.building_id=r.building_id
                  AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED')
            ORDER BY r.fact_id
            """;
    DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    DynamicElectricityRepository repository;
    DataSourceTransactionManager transactionManager;
    CarbonAuthorization authorization;
    DynamicElectricityInputReader reader;
    DynamicElectricityLiveRepository live;
    DynamicElectricityService service;

    @BeforeEach void openDatabase() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:query-day-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE biz_energy_manual_batch(batch_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),
                    status VARCHAR(32),revision INT,reviewed_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE biz_energy_manual_reading(fact_id VARCHAR(64) PRIMARY KEY,batch_id VARCHAR(32),
                    building_id VARCHAR(32),point_id VARCHAR(80),start_inclusive BIGINT,end_exclusive BIGINT,
                    reading_json VARCHAR(100000),normalized_quantity DECIMAL(38,12),supersedes_fact_id VARCHAR(64))
                """);
        jdbc.execute("CREATE INDEX fixture_manual_window ON biz_energy_manual_reading(building_id,start_inclusive,end_exclusive)");
        jdbc.execute("CREATE INDEX fixture_manual_replacement ON biz_energy_manual_reading(building_id,supersedes_fact_id)");
        jdbc.execute("""
                CREATE TABLE biz_telemetry_raw_intent(building_id VARCHAR(32),point_id VARCHAR(80),event_ms BIGINT,
                    payload_hash VARCHAR(64),storage_state VARCHAR(20),conflict_revision INT,selection_version INT)
                """);
        // 本夹具只验证查询缓存的适用边界；没有已绑定的原始电量候选测点。
        jdbc.execute("CREATE TABLE biz_dynamic_raw_electricity_point(building_id VARCHAR(32),point_id VARCHAR(80))");
        jdbc.execute("CREATE TABLE biz_quality_usage_config_revision(singleton_id INT,config_revision BIGINT)");
        jdbc.execute("CREATE TABLE biz_energy_point_profile(building_id VARCHAR(32),point_id VARCHAR(80),config_revision INT)");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("src/env/init/V104__mysql_dynamic_electricity_carbon.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("src/test/resources/dynamic-chart-projection-test-schema.sql"));
        }
        var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        repository = spy(new DynamicElectricityRepository(jdbc, mapper));
        doReturn(List.of()).when(repository).currentDeviceResults(anyString(),any(),any(),any());
        transactionManager = new DataSourceTransactionManager(dataSource);
        authorization = mock(CarbonAuthorization.class);
        reader = mock(DynamicElectricityInputReader.class);
        live = mock(DynamicElectricityLiveRepository.class);
        when(live.metadataVersion(BUILDING, repository)).thenReturn(METADATA);
        service = new DynamicElectricityService(authorization, repository, reader, transactionManager,
                evidence -> { }, new AuditGovernanceProperties(), live);
    }

    @AfterEach void shutdownDatabase() throws Exception {
        if (dataSource == null) return;
        // JdbcTemplate asks a closed H2 statement for warnings under DEBUG after SHUTDOWN.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
        dataSource = null;
    }

    TransactionTemplate readOnlyRepeatableRead() {
        return transaction(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    TransactionTemplate transaction(boolean readOnly, int isolation) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(readOnly);
        transaction.setIsolationLevel(isolation);
        transaction.setTimeout(DynamicElectricityQueryBudget.SECONDS);
        return transaction;
    }

    void batch(String id, String status, int revision) {
        jdbc.update("INSERT INTO biz_energy_manual_batch VALUES (?,?,?,?,?)", id, BUILDING, status, revision, 42L);
    }

    void fact(String id, String batch, String point, Instant from, Instant to, String json, String supersedes) {
        jdbc.update("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,?,?,?,?,?,?)",
                id, batch, BUILDING, point, from.toEpochMilli(), to.toEpochMilli(), json, new BigDecimal("1.250000000000"), supersedes);
    }

    void seedDay(int points, int hours) {
        batch("approved", "APPROVED", 1);
        var values = new ArrayList<Object[]>();
        for (int point = points - 1; point >= 0; point--) {
            for (int hour = hours - 1; hour >= 0; hour--) {
                values.add(new Object[]{factId(point, hour), "approved", BUILDING, "P" + point,
                        FROM.plusSeconds(hour * 3600L).toEpochMilli(), FROM.plusSeconds((hour + 1) * 3600L).toEpochMilli(),
                        "{\"point\":" + point + ",\"hour\":" + hour + "}", BigDecimal.valueOf(hour + 1).setScale(12), null});
            }
        }
        jdbc.batchUpdate("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,?,?,?,?,?,?)", values);
    }

    static String factId(int point, int hour) { return "point-%03d-hour-%02d".formatted(point, hour); }

    String originalVersion(Instant from, Instant to) {
        return hash(encode(jdbc.queryForList(ORIGINAL_SQL, BUILDING, to.toEpochMilli(), from.toEpochMilli())));
    }

    List<Result> saveHours(int points, int hours) {
        var factor = new DynamicElectricityCore.Factor("factor-fixed", "*", FROM, DAY_END,
                new BigDecimal("0.5"), "SIMULATED", "isolated-query-fixture", "420000", "TEST");
        repository.insertFactor(BUILDING, new FactorRow(factor, 1, hash("import"), hash("request"), ""));
        var results = new ArrayList<Result>();
        for (int hour = 0; hour < hours; hour++) {
            Instant from = FROM.plusSeconds(hour * 3600L), to = from.plusSeconds(3600);
            var factorHashes = repository.factors(BUILDING, from, to).stream().map(FactorRow::hash).toList();
            String version = hash(encode(List.of("DYNAMIC_ELECTRICITY_V3", REVIEWED, METADATA,
                    originalVersion(from, to), factorHashes)));
            BigDecimal quantity = BigDecimal.valueOf((long) points * (hour + 1));
            var applied = new DynamicElectricityCore.Applied(quantity, quantity.multiply(new BigDecimal("0.5")),
                    new BigDecimal("0.5"), List.of(factor.id()), List.of("SIMULATED"), false);
            var result = new Result(hash("result-" + hour), BUILDING, "HOUR", from, to, version, REVIEWED, applied,
                    hash("electricity-evidence-" + hour), CarbonEvidence.tree(java.util.Map.of("hour", hour, "points", points)),
                    hash(encode(factorHashes)), 1738339200000L + hour);
            repository.save(result);
            results.add(result);
        }
        return List.copyOf(results);
    }

    static ChartResult chart(Instant from, String grain, String sourceKind) {
        return new ChartResult("chart-" + from, grain, from, from.plusSeconds(3600), "unused", sourceKind,
                new ChartValues(BigDecimal.ONE, new BigDecimal("0.5"), List.of("SIMULATED")));
    }

    static List<ChartResult> twoHours(Instant from) {
        return List.of(chart(from, "HOUR", REVIEWED), chart(from.plusSeconds(3600), "HOUR", REVIEWED));
    }
}
