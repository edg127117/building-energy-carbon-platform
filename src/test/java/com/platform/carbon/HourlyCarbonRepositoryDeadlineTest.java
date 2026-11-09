package com.platform.carbon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the real JdbcTemplate lifecycle with mocked JDBC, not a database timeout/performance claim. */
class HourlyCarbonRepositoryDeadlineTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);
    private DataSource source;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet result;
    private JdbcTemplate jdbc;
    private HourlyCarbonRepository repository;
    private final AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(30));

    @BeforeEach
    void prepareJdbc() throws Exception {
        source = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);
        var statementTimeout = new AtomicInteger();
        doAnswer(call -> { statementTimeout.set(call.getArgument(0)); return null; }).when(statement).setQueryTimeout(anyInt());
        when(statement.getQueryTimeout()).thenAnswer(call -> statementTimeout.get());
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(60);
        repository = new HourlyCarbonRepository(jdbc, new ObjectMapper().findAndRegisterModules(),
                mock(AuditEvidenceWriter.class), new AuditGovernanceProperties());
    }

    @Test
    void unboundReadPreservesSqlAndTemplateTimeout() throws Exception {
        assertThat(CarbonCalculationDeadline.currentRemaining()).isNull();
        assertThat(repository.byDate("B", DAY, null)).isNull();
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).startsWith("SELECT *").doesNotContain("MAX_EXECUTION_TIME");
        verify(statement, times(1)).setQueryTimeout(60);
        verify(statement, never()).getQueryTimeout();
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void reacquiresLiveBudgetAfterConnectionWaitAndAppliesSetterAfterTemplateSettings() throws Exception {
        when(source.getConnection()).thenAnswer(call -> { remaining.set(Duration.ofSeconds(4)); return connection; });
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            remaining.set(Duration.ofMillis(1500));
            return statement;
        });
        try (var ignored = boundBudget()) { assertThat(repository.byDate("B", DAY, 2)).isNull(); }
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).startsWith("SELECT /*+ MAX_EXECUTION_TIME(4000) */")
                .contains("AND revision=?").doesNotContain("MAX_EXECUTION_TIME(0)");
        var order = inOrder(statement);
        order.verify(statement).setQueryTimeout(60);
        order.verify(statement).getQueryTimeout();
        order.verify(statement).setQueryTimeout(1);
        order.verify(statement).setObject(1, "B");
        order.verify(statement).setObject(2, java.sql.Date.valueOf(DAY));
        order.verify(statement).setObject(3, 2);
        order.verify(statement).executeQuery();
    }

    @Test
    void doesNotEnlargeExistingShorterStatementTimeout() throws Exception {
        jdbc.setQueryTimeout(1);
        remaining.set(Duration.ofSeconds(20));
        try (var ignored = boundBudget()) { repository.byDate("B", DAY, null); }
        verify(statement, times(2)).setQueryTimeout(1);
        verify(statement, never()).setQueryTimeout(20);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 999999})
    void expiredOrSubMillisecondBudgetNeverAcquiresConnectionOrDisablesServerLimit(long nanos) throws Exception {
        remaining.set(Duration.ofNanos(nanos));
        try (var ignored = boundBudget()) { assertTimeout(() -> repository.byDate("B", DAY, null)); }
        verifyNoInteractions(source, connection, statement);
    }

    @Test
    void budgetExpiredDuringConnectionAcquisitionFailsBeforePreparingSql() throws Exception {
        when(source.getConnection()).thenAnswer(call -> { remaining.set(Duration.ZERO); return connection; });
        try (var ignored = boundBudget()) { assertTimeout(() -> repository.byDate("B", DAY, null)); }
        verify(connection, never()).prepareStatement(anyString());
        verify(connection).close();
        verifyNoInteractions(statement);
    }

    @Test
    void budgetExpiredDuringStatementPreparationFailsBeforeExecutionAndClosesResources() throws Exception {
        when(connection.prepareStatement(anyString())).thenAnswer(call -> { remaining.set(Duration.ZERO); return statement; });
        try (var ignored = boundBudget()) { assertTimeout(() -> repository.byDate("B", DAY, null)); }
        verify(statement, never()).executeQuery();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void exhaustedBudgetAfterJdbcReadDoesNotReturnAnApparentlySuccessfulResult() throws Exception {
        when(result.next()).thenAnswer(call -> { remaining.set(Duration.ZERO); return false; });
        try (var ignored = boundBudget()) { assertTimeout(() -> repository.byDate("B", DAY, null)); }
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void interruptionFailsBeforeJdbcAndKeepsInterruptFlag() throws Exception {
        try (var ignored = boundBudget()) {
            try {
                Thread.currentThread().interrupt();
                assertTimeout(() -> repository.byDate("B", DAY, null));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
        verifyNoInteractions(source, connection, statement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"byId", "byKey", "replay"})
    void otherSingleRowReadEntrypointsAlsoHonorBoundRequestBudget(String route) throws Exception {
        remaining.set(Duration.ofMillis(750));
        try (var ignored = boundBudget()) {
            var stored = switch (route) {
                case "byId" -> repository.byId("batch");
                case "byKey" -> repository.byKey("B", "key");
                case "replay" -> repository.replay("B", "key", "0".repeat(64));
                default -> throw new AssertionError(route);
            };
            assertThat(stored).isNull();
        }
        verify(connection).prepareStatement(startsWith("SELECT /*+ MAX_EXECUTION_TIME(750) */"));
        verify(statement).setQueryTimeout(1);
    }

    @Test
    void boundReadPreservesIntegrityValidationAndLogsOnlyLengthsAndTiming() throws Exception {
        String privateView = "private-view-payload";
        String privateEvidence = "private-evidence-payload";
        when(result.next()).thenReturn(true, false);
        when(result.getString("batch_id")).thenReturn("batch");
        when(result.getString("building_id")).thenReturn("B");
        when(result.getDate("day_date")).thenReturn(java.sql.Date.valueOf(DAY));
        when(result.getString("view_json")).thenReturn(privateView);
        when(result.getString("view_hash")).thenReturn("0".repeat(64));
        when(result.getString("evidence_json")).thenReturn(privateEvidence);
        when(result.getString("evidence_hash")).thenReturn("0".repeat(64));
        Logger logger = (Logger) LoggerFactory.getLogger(HourlyCarbonRepository.class);
        Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try (var ignored = boundBudget()) {
            assertThatThrownBy(() -> repository.byDate("B", DAY, null)).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo("HOURLY_CARBON_INTEGRITY_VIOLATION"));
            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message).contains("building=B", "date=" + DAY, "jdbcMs=", "verificationMs=",
                    "viewChars=" + privateView.length(), "evidenceChars=" + privateEvidence.length())
                    .doesNotContain(privateView, privateEvidence, "0".repeat(64));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    private MockedStatic<CarbonCalculationDeadline> boundBudget() {
        var mocked = mockStatic(CarbonCalculationDeadline.class, CALLS_REAL_METHODS);
        mocked.when(CarbonCalculationDeadline::currentRemaining).thenAnswer(call -> remaining.get());
        return mocked;
    }

    private static void assertTimeout(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(504);
            assertThat(error.getErrorCode()).isEqualTo(CarbonErrors.CALCULATION_TIMEOUT);
        });
    }
}
