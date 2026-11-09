package com.platform.carbon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.List;
import static com.platform.carbon.HourlyCarbonReadCompressionTest.compress;
import static com.platform.carbon.HourlyCarbonReadCompressionTest.sha;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JdbcTemplate callbacks with fake JDBC; no timing thresholds or database-performance claims. */
class HourlyCarbonReadMetricsTest {
    @Test void completeCompressedReadReportsReachedStagesFramesAndOnlyOneExistingEvent() throws Exception {
        var f = new Fixture(true);
        String message = capture(() -> assertThat(f.read()).isNotNull());
        assertThat(message).contains("outcome=COMPLETE", "projection=COMPRESSED", "viewBranch=COMPRESSED", "evidenceBranch=COMPRESSED",
                "queryEntered=true", "prepareReached=true", "statementReady=true", "firstRowReached=true", "jdbcReturned=true",
                "rowCallbacks=1", "mappedRows=1", "getBytesCalls=2", "getBytesFailures=0", "decodeCalls=2", "decodeFailures=0",
                "frameBytes=" + (f.viewFrame.length + f.evidenceFrame.length), "frames=2", "nullFrames=0")
                .doesNotContain("queryEntryToPrepareCallbackMs=-1", "queryEntryToStatementReadyMs=-1", "statementReadyToFirstRowMs=-1",
                        f.evidence, f.viewJson, sha(f.viewJson));
        f.closed();
    }

    @Test void rawReadDoesNotPretendThatUnreachedCodecOrFrameWorkTookZeroTime() throws Exception {
        var f = new Fixture(false);
        String message = capture(f::read);
        assertThat(message).contains("outcome=COMPLETE", "projection=RAW", "viewBranch=RAW", "evidenceBranch=RAW",
                "getBytesMs=-1", "compressedDecodeMs=-1", "frameBytes=-1", "getBytesCalls=0", "decodeCalls=0");
        verify(f.result, never()).getBytes(anyString()); f.closed();
    }

    @Test void zeroRowsAreEmptyAndNeverBecomeSuccessfulFirstRowMeasurements() throws Exception {
        var f = new Fixture(true); when(f.result.next()).thenReturn(false);
        String message = capture(() -> assertThat(f.read()).isNull());
        assertThat(message).contains("outcome=EMPTY", "statementReady=true", "firstRowReached=false", "jdbcReturned=true",
                "statementReadyToFirstRowMs=-1", "rowCallbacks=0", "mappedRows=0", "viewBranch=NOT_REACHED", "evidenceBranch=NOT_REACHED");
        f.closed();
    }

    @Test void acquisitionFailureKeepsPrepareAndReadUnreachedAndOmitsExceptionPayload() throws Exception {
        var f = new Fixture(true); when(f.source.getConnection()).thenThrow(new SQLException("private-connection-detail"));
        String message = capture(() -> assertThatThrownBy(f::read).isInstanceOf(RuntimeException.class));
        assertThat(message).contains("outcome=FAILED", "verificationMs=-1", "queryEntered=true", "prepareReached=false",
                "statementReady=false", "firstRowReached=false", "queryEntryToPrepareCallbackMs=-1", "getBytesMs=-1")
                .doesNotContain("private-connection-detail");
        verifyNoInteractions(f.connection, f.statement, f.result);
    }

    @Test void failedBindingDoesNotMarkStatementReadyOrFirstRow() throws Exception {
        var f = new Fixture(true); doThrow(new SQLException("private-binding-detail")).when(f.statement).setObject(1, "B");
        String message = capture(() -> assertThatThrownBy(f::read).isInstanceOf(RuntimeException.class));
        assertThat(message).contains("outcome=FAILED", "prepareReached=true", "statementReady=false", "firstRowReached=false",
                "queryEntryToStatementReadyMs=-1", "statementReadyToFirstRowMs=-1", "rowCallbacks=0")
                .doesNotContain("private-binding-detail");
        verify(f.statement, never()).executeQuery(); verify(f.statement).close(); verify(f.connection).close();
    }

    @Test void frameExtractionFailureIsNotReportedAsCompletedDecodeOrMappedRow() throws Exception {
        var f = new Fixture(true); when(f.result.getBytes("view_compressed")).thenThrow(new SQLException("private-frame-detail"));
        String message = capture(() -> assertThatThrownBy(f::read).isInstanceOf(RuntimeException.class));
        assertThat(message).contains("outcome=FAILED", "firstRowReached=true", "rowCallbacks=1", "mappedRows=0",
                "getBytesCalls=1", "getBytesFailures=1", "decodeCalls=0", "compressedDecodeMs=-1", "frameBytes=-1")
                .doesNotContain("private-frame-detail");
        f.closed();
    }

    @Test void corruptFrameRecordsFailedDecodeWithoutLeakingBytesOrFallingBack() throws Exception {
        var f = new Fixture(true); byte[] bad = f.viewFrame.clone(); bad[bad.length - 1] ^= 1;
        when(f.result.getBytes("view_compressed")).thenReturn(bad);
        String message = capture(() -> assertThatThrownBy(f::read).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo("HOURLY_CARBON_INTEGRITY_VIOLATION")));
        assertThat(message).contains("outcome=FAILED", "rowCallbacks=1", "mappedRows=0", "getBytesFailures=0", "decodeCalls=1",
                "decodeFailures=1", "evidenceBranch=NOT_REACHED", "frameBytes=" + bad.length)
                .doesNotContain(java.util.HexFormat.of().formatHex(bad));
        verify(f.result, never()).getString("view_json"); f.closed();
    }

    @Test void exhaustedBudgetBeforeTemplateEntryDoesNotFabricateReachedStages() throws Exception {
        var f = new Fixture(true);
        try (var mocked = mockStatic(CarbonCalculationDeadline.class, CALLS_REAL_METHODS)) {
            mocked.when(CarbonCalculationDeadline::currentRemaining).thenReturn(Duration.ZERO);
            String message = capture(() -> assertThatThrownBy(f::read).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo("CARBON_CALCULATION_TIMEOUT")));
            assertThat(message).contains("outcome=FAILED", "queryEntered=false", "prepareReached=false", "statementReady=false",
                    "firstRowReached=false", "queryEntryToPrepareCallbackMs=-1", "getBytesMs=-1", "compressedDecodeMs=-1");
        }
        verifyNoInteractions(f.source, f.connection, f.statement, f.result);
    }

    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static String capture(Checked action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HourlyCarbonRepository.class);
        Level old = logger.getLevel(); var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender); logger.setLevel(Level.INFO);
        try (var ignored = CarbonCalculationDeadline.start(Duration.ofSeconds(30)).bind()) {
            action.run(); assertThat(appender.list).hasSize(1);
            return appender.list.getFirst().getFormattedMessage();
        } finally { logger.detachAppender(appender); logger.setLevel(old); appender.stop(); }
    }

    private static final class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet result = mock(ResultSet.class);
        final HourlyCarbonRepository repository;
        final String evidence = "{\"private-synthetic-evidence\":true}", viewJson;
        final byte[] viewFrame, evidenceFrame;
        Fixture(boolean compression) throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true, false);
            var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            repository = new HourlyCarbonRepository(new JdbcTemplate(source), mapper, mock(AuditEvidenceWriter.class), new AuditGovernanceProperties());
            ReflectionTestUtils.setField(repository, "readCompression", compression);
            Instant asOf = Instant.parse("2025-03-01T00:00:00Z");
            var view = new HourlyCarbonContracts.View("B", LocalDate.of(2025, 2, 1), "Asia/Shanghai", "KG_CO2E",
                    "PURCHASED_ELECTRICITY_SCOPE_2", "RAW_CUMULATIVE", "SOURCE_LABELLED", "FORMAL", "MONITORING", "PARTIAL",
                    "batch", 1, asOf, asOf, "private-synthetic-view", 24, 0, java.math.BigDecimal.ZERO, List.of(), asOf, sha(evidence));
            viewJson = ReflectionTestUtils.invokeMethod(repository, "canonicalJson", view);
            viewFrame = compress(viewJson.getBytes(StandardCharsets.UTF_8)); evidenceFrame = compress(evidence.getBytes(StandardCharsets.UTF_8));
            when(result.getString("batch_id")).thenReturn("batch"); when(result.getString("building_id")).thenReturn("B");
            when(result.getDate("day_date")).thenReturn(java.sql.Date.valueOf("2025-02-01")); when(result.getInt("revision")).thenReturn(1);
            when(result.getLong("calculation_as_of")).thenReturn(asOf.toEpochMilli());
            when(result.getString("view_json")).thenReturn(viewJson); when(result.getString("view_hash")).thenReturn(sha(viewJson));
            when(result.getString("evidence_json")).thenReturn(evidence); when(result.getString("evidence_hash")).thenReturn(sha(evidence));
            when(result.getLong("view_utf8_bytes")).thenReturn((long) viewJson.getBytes(StandardCharsets.UTF_8).length);
            when(result.getBytes("view_compressed")).thenReturn(viewFrame); when(result.getBytes("evidence_compressed")).thenReturn(evidenceFrame);
        }
        HourlyCarbonRepository.Stored read() { return repository.byDate("B", LocalDate.of(2025, 2, 1), null); }
        void closed() throws Exception { verify(result).close(); verify(statement).close(); verify(connection).close(); }
    }
}
