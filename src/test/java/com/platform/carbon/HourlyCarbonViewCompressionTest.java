package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.platform.carbon.HourlyCarbonReadCompressionTest.compress;
import static com.platform.carbon.HourlyCarbonReadCompressionTest.sha;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Transport and JdbcTemplate lifecycle checks; these do not assert production query performance. */
class HourlyCarbonViewCompressionTest {
    private static final int CAP = 16 * 1024 * 1024;
    private static final LocalDate DAY = LocalDate.of(2025, 2, 1);

    @Test void compressesBothCurrentBodiesWithoutChangingVerifiedViewOrLatestSelector() throws Exception {
        var f = new Fixture("Unicode \u4e2d\u6587 \ud83d\ude00");
        var raw = f.read(false, null);
        clearInvocations(f.result, f.connection, f.statement);
        HourlyCarbonRepository.Stored compressed;
        try (var ignored = CarbonCalculationDeadline.start(Duration.ofSeconds(30)).bind()) {
            compressed = f.read(true, null);
        }
        assertThat(compressed).isEqualTo(raw);
        assertThat(compressed.evidenceJson()).isEqualTo(f.evidence);
        assertThat(compressed.view()).isEqualTo(f.view);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(f.connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("MAX_EXECUTION_TIME(",
                "OCTET_LENGTH(CONVERT(view_json USING utf8mb4)) AS view_utf8_bytes",
                "<=16777216 THEN COMPRESS(CONVERT(view_json USING utf8mb4)) END AS view_compressed",
                ">16777216 THEN view_json END AS view_json",
                "COMPRESS(CONVERT(evidence_json USING utf8mb4)) AS evidence_compressed",
                "WHERE building_id=? AND day_date=?", "ORDER BY calculation_as_of DESC, revision DESC", "LIMIT 1");
        verify(f.statement).setObject(1, "B");
        verify(f.statement).setObject(2, java.sql.Date.valueOf(DAY));
        verify(f.result).getBytes("view_compressed");
        verify(f.result).getBytes("evidence_compressed");
        verify(f.result, never()).getString("view_json");
        verify(f.result, never()).getString("evidence_json");
        f.verifyClosed();
    }

    @Test void preservesRawOptOutAndExplicitRevisionBinding() throws Exception {
        var f = new Fixture("test");
        f.read(false, 7);
        verify(f.connection).prepareStatement(startsWith("SELECT *"));
        verify(f.result, never()).getLong("view_utf8_bytes");
        verify(f.result, never()).getBytes(anyString());
        clearInvocations(f.connection, f.statement, f.result);
        try (var ignored = CarbonCalculationDeadline.start(Duration.ofSeconds(30)).bind()) {
            f.read(true, 7);
        }
        verify(f.connection).prepareStatement(contains("WHERE building_id=? AND day_date=? AND revision=?"));
        verify(f.statement).setObject(3, 7);
        f.verifyClosed();
    }

    @Test void rejectsNullTruncatedCorruptOrOversizedViewFramesWithoutReadingRawFallback() throws Exception {
        var f = new Fixture("test");
        byte[] frame = compress(f.viewJson.getBytes(StandardCharsets.UTF_8));
        byte[] corrupt = frame.clone(); corrupt[corrupt.length - 1] ^= 1;
        byte[] oversized = frame.clone(); ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(CAP + 1);
        for (byte[] invalid : new byte[][]{null, Arrays.copyOf(frame, frame.length - 1), corrupt, oversized}) {
            when(f.result.getBytes("view_compressed")).thenReturn(invalid);
            assertIntegrity(() -> f.read(true, null));
        }
        verify(f.result, never()).getString("view_json");
        verify(f.result, times(4)).close();
        verify(f.statement, times(4)).close();
        verify(f.connection, times(4)).close();
    }

    @Test void rejectsInvalidUtf8NullLengthNegativeLengthAndCharacterCountUsedAsByteLength() throws Exception {
        var f = new Fixture("\u4e2d\u6587\ud83d\ude00");
        assertThat(f.viewJson.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(f.viewJson.length());
        when(f.result.getLong("view_utf8_bytes")).thenReturn((long) f.viewJson.length());
        assertIntegrity(() -> f.read(true, null));
        when(f.result.getLong("view_utf8_bytes")).thenReturn(-1L);
        assertIntegrity(() -> f.read(true, null));
        when(f.result.getLong("view_utf8_bytes")).thenReturn(0L);
        when(f.result.wasNull()).thenReturn(true);
        assertIntegrity(() -> f.read(true, null));
        when(f.result.wasNull()).thenReturn(false);
        when(f.result.getLong("view_utf8_bytes")).thenReturn(2L);
        when(f.result.getBytes("view_compressed")).thenReturn(compress(new byte[]{(byte) 0xc3, 0x28}));
        assertIntegrity(() -> f.read(true, null));
        verify(f.result, never()).getString("view_json");
    }

    @Test void keepsFullViewHashCanonicalAndIdentityValidationAfterDecompression() throws Exception {
        var f = new Fixture("test");
        f.transport(f.viewJson.replace("\"test\"", "\"changed\""));
        assertIntegrity(() -> f.read(true, null));
        String noncanonical = " " + f.viewJson;
        f.transport(noncanonical);
        when(f.result.getString("view_hash")).thenReturn(sha(noncanonical));
        assertIntegrity(() -> f.read(true, null));
        f.transport(f.viewJson);
        when(f.result.getString("view_hash")).thenReturn(sha(f.viewJson));
        when(f.result.getString("building_id")).thenReturn("OTHER");
        assertIntegrity(() -> f.read(true, null));
        verify(f.result, never()).getString("view_json");
    }

    @Test void exactTransportCapStillDecodesAndLargerPublishedViewsKeepTheOriginalRawPath() throws Exception {
        var result = mock(ResultSet.class);
        String boundary = "a".repeat(CAP);
        when(result.getLong("view_utf8_bytes")).thenReturn((long) CAP);
        when(result.getBytes("view_compressed")).thenReturn(compress(boundary.getBytes(StandardCharsets.UTF_8)));
        String decoded = ReflectionTestUtils.invokeMethod(HourlyCarbonRepository.class, "compressedView", result);
        assertThat(decoded).isEqualTo(boundary);
        verify(result, never()).getString("view_json");

        // The existing publisher has no view-size limit. A valid oversized historical view is not a new error.
        var f = new Fixture("a".repeat(CAP));
        assertThat(f.viewJson.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(CAP);
        var original = f.read(false, null);
        clearInvocations(f.result, f.connection, f.statement);
        when(f.result.getBytes("view_compressed")).thenThrow(new AssertionError("oversized view must stay raw"));
        assertThat(f.read(true, null)).isEqualTo(original);
        verify(f.result).getString("view_json");
        verify(f.result, never()).getBytes("view_compressed");
        f.verifyClosed();
    }

    @Test void budgetExpiredDuringCompressedViewReadStillFailsAndReleasesJdbcResources() throws Exception {
        var f = new Fixture("test");
        var remaining = new AtomicReference<>(Duration.ofSeconds(30));
        byte[] frame = compress(f.viewJson.getBytes(StandardCharsets.UTF_8));
        when(f.result.getBytes("view_compressed")).thenAnswer(call -> {
            remaining.set(Duration.ZERO);
            return frame;
        });
        try (var mocked = mockStatic(CarbonCalculationDeadline.class, CALLS_REAL_METHODS)) {
            mocked.when(CarbonCalculationDeadline::currentRemaining).thenAnswer(call -> remaining.get());
            assertThatThrownBy(() -> f.read(true, null)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo("CARBON_CALCULATION_TIMEOUT"));
        }
        f.verifyClosed();
    }

    private static void assertIntegrity(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("HOURLY_CARBON_INTEGRITY_VIOLATION"));
    }

    private static final class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet result = mock(ResultSet.class);
        final HourlyCarbonRepository repository;
        final HourlyCarbonContracts.View view;
        final String viewJson;
        final String evidence = "{\"original\":true}";

        Fixture(String meaning) throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(result);
            var mapper = new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            repository = new HourlyCarbonRepository(new JdbcTemplate(source), mapper,
                    mock(AuditEvidenceWriter.class), new AuditGovernanceProperties());
            Instant asOf = Instant.parse("2025-03-01T00:00:00Z");
            view = new HourlyCarbonContracts.View("B", DAY, "Asia/Shanghai", "KG_CO2E",
                    "PURCHASED_ELECTRICITY_SCOPE_2", "RAW_CUMULATIVE", "SOURCE_LABELLED", "FORMAL", "MONITORING", "PARTIAL",
                    "batch", 7, asOf, asOf, meaning, 24, 0, java.math.BigDecimal.ZERO, List.of(), asOf, sha(evidence));
            viewJson = ReflectionTestUtils.invokeMethod(repository, "canonicalJson", view);
            when(result.getDate("day_date")).thenReturn(java.sql.Date.valueOf(DAY));
            when(result.getString("batch_id")).thenReturn("batch");
            when(result.getString("building_id")).thenReturn("B");
            when(result.getInt("revision")).thenReturn(7);
            when(result.getLong("calculation_as_of")).thenReturn(asOf.toEpochMilli());
            when(result.getString("view_json")).thenReturn(viewJson);
            when(result.getString("view_hash")).thenReturn(sha(viewJson));
            when(result.getString("evidence_json")).thenReturn(evidence);
            when(result.getString("evidence_hash")).thenReturn(sha(evidence));
            when(result.getBytes("evidence_compressed")).thenReturn(compress(evidence.getBytes(StandardCharsets.UTF_8)));
            transport(viewJson);
        }

        void transport(String value) throws Exception {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            when(result.getLong("view_utf8_bytes")).thenReturn((long) utf8.length);
            when(result.getBytes("view_compressed")).thenReturn(compress(utf8));
        }

        HourlyCarbonRepository.Stored read(boolean compressed, Integer revision) throws Exception {
            ReflectionTestUtils.setField(repository, "readCompression", compressed);
            when(result.next()).thenReturn(true, false);
            return repository.byDate("B", DAY, revision);
        }

        void verifyClosed() throws Exception {
            verify(result).close();
            verify(statement).close();
            verify(connection).close();
        }
    }
}
