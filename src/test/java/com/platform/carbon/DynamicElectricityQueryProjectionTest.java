package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.platform.carbon.DynamicElectricityRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离H2验证图表读取的正文边界与原Result校验，不连接实际数据库。 */
class DynamicElectricityQueryProjectionTest {
    private static final Instant FROM = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(31 * 86400L);
    private JdbcTemplate jdbc;
    private ObjectMapper json;
    private DynamicElectricityRepository repository;
    private TrackingDataSource tracked;
    private boolean databaseClosed;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:dynamic-chart-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false", "sa", "");
        // 只保留本用例的独立内存库，结束时显式关闭，避免大正文累积到其他测试。
        source.setUrl(source.getUrl() + ";DB_CLOSE_DELAY=-1");
        tracked = new TrackingDataSource(source);
        jdbc = spy(new JdbcTemplate(tracked));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("src/env/init/V104__mysql_dynamic_electricity_carbon.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("src/test/resources/dynamic-chart-projection-test-schema.sql"));
        }
        json = spy(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        repository = new DynamicElectricityRepository(jdbc, json);
    }

    @AfterEach void closeDatabase() throws Exception {
        try { tracked.assertClosed(); }
        finally { shutdownDatabase(); }
    }

    private void shutdownDatabase() throws SQLException {
        if (databaseClosed) return;
        // SHUTDOWN 会立即关闭数据库，不能再让 JdbcTemplate 的 DEBUG 路径查询 statement warnings。
        try (var connection = tracked.source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
            databaseClosed = true;
        }
    }

    @Test void stagedPublicationPreservesVerifiedPayloadBytesAndFullEvidence() {
        var full=result("staged","B","HOUR",FROM,FROM.plusSeconds(3600),1);
        String payload=encode(full);
        assertThat(repository.saveStaged(payload,hash(payload))).isEqualTo(full);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM biz_dynamic_electricity_result WHERE result_id=?",String.class,full.id()))
                .isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM biz_dynamic_electricity_result WHERE result_id=?",String.class,full.id()))
                .isEqualTo(hash(payload));
        assertThat(repository.byId("B",full.id())).isEqualTo(full);
    }

    @Test void stagedPublicationRejectsCorruptDigestAndInvalidNonChartEvidenceBeforeWrite() {
        var full=result("bad-staged","B","HOUR",FROM,FROM.plusSeconds(3600),1);
        String payload=encode(full);
        assertThatThrownBy(()->repository.saveStaged(payload,hash("wrong"))).isInstanceOf(IllegalStateException.class);
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)CarbonEvidence.parse(payload);
        tree.putObject("capturedAt").put("invalid",true);String invalid=CarbonEvidence.canonical(tree);
        assertThatThrownBy(()->repository.saveStaged(invalid,hash(invalid))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_electricity_result",Integer.class)).isZero();
    }

    @Test void retainsExactChartFieldsButFullEvidenceRemainsAvailableOnlyById() {
        var full = result("one", "B", "HOUR", FROM, FROM.plusSeconds(3600), 1);
        repository.save(full);
        clearInvocations(jdbc);

        var chart = repository.results("B", FROM, TO);

        assertThat(chart).containsExactly(ChartResult.from(full));
        assertThat(chart.getFirst().values().quantity()).isEqualTo(new BigDecimal("219.123456789012345678"));
        assertThat(chart.getFirst().values().emissionKgCo2()).isEqualTo(new BigDecimal("109.561728394506172839"));
        assertThat(chart.getFirst().values().sourceNatures()).containsExactly("SIMULATED");
        var serialized = json.valueToTree(chart.getFirst());
        assertThat(serialized.has("electricityEvidence")).isFalse();
        assertThat(serialized.path("values").has("factorIds")).isFalse();
        assertThat(queries()).hasSize(1);
        assertThat(queries().getFirst()).contains("LEFT JOIN biz_dynamic_electricity_chart_projection", "LIMIT 50001")
                .contains("chart_json", "chart_hash", "evidence_hash").doesNotContain("r.payload_json", "OCTET_LENGTH");
        assertThat(repository.byId("B", full.id())).isEqualTo(full);
        assertThat(repository.byId("OTHER", full.id())).isNull();
    }

    @Test void savesImmutableProjectionWithEvidenceHashAndAllowsIdempotentReplay() {
        var full=result("projection-replay","B","HOUR",FROM,FROM.plusSeconds(3600),3);
        repository.save(full);
        repository.save(full);
        assertThat(jdbc.queryForObject("SELECT evidence_hash FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",
                String.class,full.id())).isEqualTo(hash(encode(full)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",
                Integer.class,full.id())).isEqualTo(1);
        assertThat(repository.results("B",FROM,TO)).containsExactly(ChartResult.from(full));
    }

    @Test void missingProjectionReturnsPreparingWithoutReadingTheFullEvidence() throws Exception {
        var full=result("projection-missing","B","HOUR",FROM,FROM.plusSeconds(3600),4);
        repository.save(full);
        jdbc.update("DELETE FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",full.id());
        clearInvocations(json,jdbc);

        assertThatThrownBy(()->repository.results("B",FROM,TO)).hasMessageContaining("投影尚未就绪");

        verify(json,never()).readValue(anyString(),eq(Result.class));
        assertThat(queries()).singleElement().asString().doesNotContain("payload_json");
    }

    @Test void corruptedProjectionFailsClosedAndNeverFallsBackToTheEvidenceBody() throws Exception {
        var full=result("projection-corrupt","B","HOUR",FROM,FROM.plusSeconds(3600),5);
        repository.save(full);
        jdbc.update("UPDATE biz_dynamic_electricity_chart_projection SET chart_hash=? WHERE result_id=?",hash("wrong"),full.id());
        clearInvocations(json,jdbc);

        assertThatThrownBy(()->repository.results("B",FROM,TO)).hasMessageContaining("投影摘要不一致");

        verify(json,never()).readValue(anyString(),eq(Result.class));
        assertThat(queries()).singleElement().asString().doesNotContain("payload_json");
    }

    @Test void backfillFullyValidatesEvidenceAndPersistsOnlyRecoverableRows() {
        var full=result("projection-backfill","B","HOUR",FROM,FROM.plusSeconds(3600),6);
        repository.save(full);
        jdbc.update("DELETE FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",full.id());
        assertThat(repository.backfillChartProjections(10)).isEqualTo(1);
        assertThat(repository.results("B",FROM,TO)).containsExactly(ChartResult.from(full));

        jdbc.update("DELETE FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",full.id());
        jdbc.update("UPDATE biz_dynamic_electricity_result SET payload_hash=? WHERE result_id=?",hash("wrong"),full.id());
        assertThat(repository.backfillChartProjections(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT integrity_error FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",
                String.class,full.id())).isNotNull();
        assertThatThrownBy(()->repository.results("B",FROM,TO)).hasMessageContaining("完整性巡检未通过");
    }

    @Test void preservesWindowScopeGrainExclusionAndOriginalOrdering() {
        var day = result("day", "B", "DAY", FROM, FROM.plusSeconds(86400), 1);
        var older = result("older", "B", "HOUR", FROM, FROM.plusSeconds(3600), 1);
        var newer = result("newer", "B", "HOUR", FROM, FROM.plusSeconds(3600), 2);
        var later = result("later", "B", "HOUR", FROM.plusSeconds(3600), FROM.plusSeconds(7200), 1);
        List.of(later, older, day, newer,
                result("foreign", "OTHER", "HOUR", FROM, FROM.plusSeconds(3600), 1),
                result("outside", "B", "MONTH", FROM, TO.plusSeconds(1), 1),
                result("earlier", "B", "HOUR", FROM.minusMillis(1), FROM.plusSeconds(3600), 1),
                result("interval", "B", "INTERVAL", FROM, FROM.plusSeconds(900), 1),
                result("lock", "B", "LOCK", FROM, FROM.plusSeconds(1), 1)).forEach(repository::save);

        assertThat(repository.results("B", FROM, TO)).extracting(ChartResult::id)
                .containsExactly(day.id(), newer.id(), older.id(), later.id());
        assertThat(repository.results("NONE", FROM, TO)).isEmpty();
    }

    @Test void includesExactWindowBoundariesAndEmptyQueryReadsNoBody() {
        var exact = result("exact", "B", "MONTH", FROM, TO, 1);
        repository.save(exact);
        assertThat(repository.results("B", FROM, TO)).containsExactly(ChartResult.from(exact));
        clearInvocations(jdbc);

        assertThat(repository.results("NONE", FROM, TO)).isEmpty();

        assertThat(queries()).singleElement().asString().contains("LEFT JOIN biz_dynamic_electricity_chart_projection").doesNotContain("r.payload_json");
    }

    @Test void fullBodyChangesCannotBeHiddenByAProjectionEvidenceReference() throws Exception {
        var full = result("corrupt", "B", "HOUR", FROM, FROM.plusSeconds(3600), 1);
        repository.save(full);
        jdbc.update("UPDATE biz_dynamic_electricity_result SET payload_hash=? WHERE result_id=?", hash("wrong"), full.id());
        assertThatThrownBy(() -> repository.results("B", FROM, TO)).hasMessageContaining("证据引用不一致");

        var invalid = json.readTree(encode(full));
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).putObject("electricityHash").put("invalid", true);
        String payload = json.writeValueAsString(invalid);
        jdbc.update("UPDATE biz_dynamic_electricity_result SET payload_json=?,payload_hash=? WHERE result_id=?",
                payload, hash(payload), full.id());
        jdbc.update("DELETE FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",full.id());
        assertThat(repository.backfillChartProjections(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT integrity_error FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",
                String.class,full.id())).isNotNull();
        assertThatThrownBy(()->repository.results("B",FROM,TO)).hasMessageContaining("完整性巡检未通过");
    }

    @Test void oversizedEvidenceIsDetectedByTheBoundedIntegrityAudit() throws Exception {
        var full = result("oversized", "B", "HOUR", FROM, FROM.plusSeconds(3600), 1);
        repository.save(full);
        String oversized = "测".repeat(5_333_334);
        jdbc.update("UPDATE biz_dynamic_electricity_result SET payload_json=? WHERE result_id=?", oversized, full.id());
        clearInvocations(json, jdbc);

        assertThat(repository.results("B", FROM, TO)).hasSize(1);
        assertThat(queries()).hasSize(1);
        jdbc.update("UPDATE biz_dynamic_electricity_chart_projection SET last_verified_at=0 WHERE result_id=?",full.id());
        assertThat(repository.verifyChartProjectionIntegrity(16)).isEqualTo(new IntegrityAudit(1,1));
        assertThatThrownBy(()->repository.results("B",FROM,TO)).hasMessageContaining("完整性巡检未通过");

        verify(json, never()).readValue(anyString(), eq(Result.class));
        assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(payload_json) FROM biz_dynamic_electricity_result WHERE result_id=?",
                Long.class, full.id())).isEqualTo(16_000_002L);
        assertThat(jdbc.queryForObject("SELECT integrity_error FROM biz_dynamic_electricity_chart_projection WHERE result_id=?",
                String.class,full.id())).isEqualTo("DYNAMIC_CAPACITY");
    }

    @Test void yearlySizedProjectionReadUsesOneStatementAndNeverSelectsEvidenceBodies() {
        for (int index = 0; index < 48; index++) {
            repository.save(result("row-" + index, "B", "HOUR", FROM.plusSeconds(index * 3600L),
                    FROM.plusSeconds((index + 1) * 3600L), index));
        }
        clearInvocations(jdbc);

        var chart = repository.results("B", FROM, TO);

        assertThat(chart).hasSize(48);
        assertThat(queries()).hasSize(1);
        assertThat(queries().getFirst()).contains("LEFT JOIN biz_dynamic_electricity_chart_projection")
                .doesNotContain("r.payload_json", "OCTET_LENGTH");
        assertThat(chart.stream().map(r -> r.values().quantity()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("219.123456789012345678").multiply(new BigDecimal("48")));
    }

    @Test void largeEvidenceDoesNotIncreaseChartQueryWork() {
        var large=result("large","B","HOUR",FROM,FROM.plusSeconds(3600),1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)large.electricityEvidence()).put("largeDetail","x".repeat(1_100_000));
        repository.save(large);
        repository.save(result("small-a","B","HOUR",FROM.plusSeconds(3600),FROM.plusSeconds(7200),1));
        repository.save(result("small-b","B","HOUR",FROM.plusSeconds(7200),FROM.plusSeconds(10800),1));
        clearInvocations(jdbc);

        assertThat(repository.results("B",FROM,TO)).extracting(ChartResult::id)
                .containsExactly(large.id(),hash("small-a"),hash("small-b"));

        assertThat(queries()).hasSize(1);
        assertThat(queries().getFirst()).contains("LEFT JOIN biz_dynamic_electricity_chart_projection")
                .doesNotContain("r.payload_json", "OCTET_LENGTH");
    }

    @Test void acceptsExactMaximumPayloadBytesWithoutRequiringACallerTransaction() {
        var full = result("maximum", "B", "HOUR", FROM, FROM.plusSeconds(3600), 1);
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) full.electricityEvidence();
        evidence.put("padding", "");
        int paddingBytes = MAX_RESULT_PAYLOAD_BYTES - encode(full).getBytes(StandardCharsets.UTF_8).length;
        evidence.put("padding", "x".repeat(paddingBytes));
        String payload = encode(full);
        assertThat(payload.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_RESULT_PAYLOAD_BYTES);
        repository.saveStaged(payload, hash(payload));
        clearInvocations(jdbc);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(repository.results("B", FROM, TO)).containsExactly(ChartResult.from(full));

        assertThat(queries()).hasSize(1);
        assertThat(queries().getFirst()).contains("LEFT JOIN biz_dynamic_electricity_chart_projection").doesNotContain("r.payload_json");
    }

    @Test void foreignBuildingResultIsExcludedByTheBoundedQuery() {
        var foreign = result("foreign-body", "OTHER", "HOUR", FROM, FROM.plusSeconds(3600), 1);
        repository.save(foreign);
        assertThat(repository.results("B",FROM,TO)).isEmpty();
    }

    @Test void preservesJdbcTimeoutAndClosesStatementsWhenProjectionQueryFails() {
        repository.save(result("sql-failure", "B", "HOUR", FROM, FROM.plusSeconds(3600), 1));
        jdbc.setQueryTimeout(7);
        tracked.failureSql = "LEFT JOIN biz_dynamic_electricity_chart_projection";
        tracked.executions.clear();
        clearInvocations(jdbc);

        assertThatThrownBy(() -> repository.results("B", FROM, TO))
                .isInstanceOf(DataAccessException.class).hasRootCauseMessage("forced chart SQL failure");

        assertThat(queries()).hasSize(1);
        assertThat(tracked.executions).allSatisfy(read -> assertThat(read.timeout()).isEqualTo(7));
        tracked.assertClosed();
    }

    @Test void usesTheCallersRepeatableReadConnectionAndTransactionTimeoutForEveryRead() {
        repository.save(result("transaction", "B", "HOUR", FROM, FROM.plusSeconds(3600), 1));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(tracked));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(15);
        tracked.executions.clear();

        transaction.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(repository.results("B", FROM, TO)).hasSize(1);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        });

        assertThat(tracked.executions).hasSize(1).allSatisfy(read -> {
            assertThat(read.connection()).isSameAs(tracked.executions.getFirst().connection());
            assertThat(read.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(read.timeout()).isBetween(1, 15);
        });
        tracked.assertClosed();
    }

    @Test void rawJdbcShutdownDoesNotInspectClosedStatementWarningsWhenDebugIsEnabled() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JdbcTemplate.class);
        var previous = logger.getLevel();
        try {
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            assertThat(repository.results("B", FROM, TO)).isEmpty();
            clearInvocations(jdbc);

            assertThatCode(this::shutdownDatabase).doesNotThrowAnyException();

            verifyNoInteractions(jdbc);
        } finally {
            logger.setLevel(previous);
        }
    }

    private ResultSet bodyRow(Result full) throws SQLException {
        String payload = encode(full);
        var row = mock(ResultSet.class);
        when(row.getString("result_id")).thenReturn(full.id());
        when(row.getLong("payload_bytes")).thenReturn((long) payload.getBytes(StandardCharsets.UTF_8).length);
        when(row.getString("payload_json")).thenReturn(payload);
        when(row.getString("payload_hash")).thenReturn(hash(payload));
        return row;
    }

    private DynamicElectricityRepository stubbedBody(String id, ResultSet row, boolean duplicate) {
        var mocked = mock(JdbcTemplate.class);
        doReturn(List.of(id)).when(mocked).query(contains("SELECT result_id FROM"), any(RowMapper.class), any(), any(), any());
        doAnswer(call -> {
            PreparedStatementSetter setter = call.getArgument(1);
            setter.setValues(mock(PreparedStatement.class));
            RowMapper<ChartResult> mapper = call.getArgument(2);
            var first = mapper.mapRow(row, 0);
            return duplicate ? List.of(first, mapper.mapRow(row, 1)) : List.of(first);
        }).when(mocked).query(contains("CASE WHEN"), any(PreparedStatementSetter.class), any(RowMapper.class));
        return new DynamicElectricityRepository(mocked, json);
    }

    private Object[] boundArguments(PreparedStatementSetter setter) {
        var values = new java.util.TreeMap<Integer,Object>();
        var statement = mock(PreparedStatement.class, call -> {
            if (call.getMethod().getName().startsWith("set")) values.put(call.getArgument(0), call.getArgument(1));
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        try { setter.setValues(statement); } catch (SQLException failure) { throw new AssertionError(failure); }
        return values.values().toArray();
    }

    private void assertBodyBudgets() {
        mockingDetails(jdbc).getInvocations().forEach(call->{
            Object[] raw=call.getRawArguments();
            if(raw.length!=3 || !(raw[1] instanceof PreparedStatementSetter) || !(raw[2] instanceof RowMapper<?>)
                    || !(raw[0] instanceof String sql) || !sql.contains("CASE WHEN"))return;
            Object[] args=boundArguments((PreparedStatementSetter)raw[1]);
            assertThat(args).hasSize(3);
            assertThat(args[0]).isEqualTo(MAX_RESULT_PAYLOAD_BYTES);
            assertThat(args[1]).isEqualTo("B");
            assertThat(args[2]).isInstanceOf(String.class);
            assertThat(sql).contains("CASE WHEN OCTET_LENGTH(payload_json)<=? THEN payload_json ELSE NULL END",
                    "payload_hash,OCTET_LENGTH(payload_json) AS payload_bytes", "WHERE building_id=? AND result_id=?")
                    .doesNotContain(" IN (");
        });
    }

    private List<String> bodyIds() {
        var ids = new ArrayList<String>();
        mockingDetails(jdbc).getInvocations().forEach(call -> {
            Object[] raw = call.getRawArguments();
            if (raw.length == 3 && raw[1] instanceof PreparedStatementSetter && raw[2] instanceof RowMapper<?> && raw[0] instanceof String sql && sql.contains("CASE WHEN"))
                ids.add((String) boundArguments((PreparedStatementSetter)raw[1])[2]);
        });
        return ids;
    }

    private List<String> queries() {
        var values = new ArrayList<String>();
        mockingDetails(jdbc).getInvocations().forEach(call -> {
            Object[] arguments = call.getRawArguments();
            boolean repositoryCall = arguments.length == 3 && (arguments[1] == String.class
                    || arguments[1] instanceof org.springframework.jdbc.core.RowMapper<?>
                    || (arguments[1] instanceof PreparedStatementSetter && arguments[2] instanceof RowMapper<?> && arguments[0] instanceof String body && body.contains("CASE WHEN")));
            if (repositoryCall && arguments[0] instanceof String sql && sql.stripLeading().startsWith("SELECT"))
                values.add(sql);
        });
        return values;
    }

    private record Execution(Connection connection, int timeout, int isolation) { }

    private static final class TrackingDataSource extends AbstractDataSource {
        private final DriverManagerDataSource source;
        private final List<Connection> connections = new ArrayList<>();
        private final List<PreparedStatement> statements = new ArrayList<>();
        private final List<ResultSet> resultSets = new ArrayList<>();
        private final List<Execution> executions = new ArrayList<>();
        private String failureSql;

        private TrackingDataSource(DriverManagerDataSource source) { this.source = source; }

        @Override public Connection getConnection() throws SQLException { return track(source.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return track(source.getConnection(user, password));
        }

        private Connection track(Connection connection) {
            connections.add(connection);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(connection, args);
                            if (value instanceof PreparedStatement statement) {
                                statements.add(statement);
                                return track(statement, connection, (String) args[0]);
                            }
                            return value;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private PreparedStatement track(PreparedStatement statement, Connection connection, String sql) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        try {
                            if (method.getName().equals("executeQuery")) {
                                executions.add(new Execution(connection, statement.getQueryTimeout(), connection.getTransactionIsolation()));
                                if (failureSql != null && sql.contains(failureSql))
                                    throw new SQLException("forced chart SQL failure", "42000");
                            }
                            Object value = method.invoke(statement, args);
                            if (value instanceof ResultSet resultSet) resultSets.add(resultSet);
                            return value;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private void assertClosed() {
            try {
                for (var resultSet : resultSets) assertThat(resultSet.isClosed()).isTrue();
                for (var statement : statements) assertThat(statement.isClosed()).isTrue();
                for (var connection : connections) assertThat(connection.isClosed()).isTrue();
            } catch (SQLException failure) {
                throw new AssertionError("Cannot verify JDBC resource cleanup", failure);
            }
        }
    }

    private Result result(String key, String building, String grain, Instant from, Instant to, long captured) {
        var evidence = json.createObjectNode();
        var points = evidence.putArray("syntheticContributions");
        for (int point = 0; point < 219; point++) {
            var row = points.addObject();
            row.put("pointId", "SYNTHETIC_" + point);
            row.put("electricityKwh", "1.00056372963019335926027397260274");
            row.putArray("ancestors").add("TOTAL").add("FLOOR_" + point % 6).add("POINT_" + point);
            row.putArray("evidenceIds").add("test-evidence-" + point);
        }
        var values = new DynamicElectricityCore.Applied(new BigDecimal("219.123456789012345678"),
                new BigDecimal("109.561728394506172839"), new BigDecimal("0.5"),
                List.of("factor-test"), List.of("SIMULATED"), false);
        return new Result(hash(key), building, grain, from, to, hash("version-" + key), "REVIEWED_INTERVAL", values,
                hash("electricity"), evidence, hash("factor"), captured);
    }
}
