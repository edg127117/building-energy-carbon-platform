package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HourlyCarbonReadCompressionTest {
    // Fixed actual MySQL 8.0.46 output, independently collected with constant-only SELECTs.
    // Evidence hashes and provenance are recorded in MYSQL-GOLDENS.md; no Deflater generates these frames.
    private static final String[][] MYSQL_GOLDENS = {
            {"48656C6C6F204D7953514C", "0B000000789CF348CDC9C957F0AD0C0EF40100175603CB"},
            {"E4B8ADE69687F09F9880", "0A000000789C7BB263EDB369ED1FE6CF680000289006F4"},
            {"61626320", "04000000789C4B4C4A56000003940147"},
            {"1F", "01000000789C930700002000202E"},
            {"", ""}
    };

    @Test void decodesActualMysqlGoldenFramesToExactlyTheOriginalBytes() {
        for (String[] golden : MYSQL_GOLDENS) {
            byte[] original = java.util.HexFormat.of().parseHex(golden[0]);
            byte[] frame = java.util.HexFormat.of().parseHex(golden[1]);
            String decoded = MysqlCompressedText.decode(frame, 16 * 1024 * 1024);
            assertThat(decoded).isEqualTo(new String(original, StandardCharsets.UTF_8));
            assertThat(decoded.getBytes(StandardCharsets.UTF_8)).containsExactly(original);
        }
        assertThat(MysqlCompressedText.decode(null, 16 * 1024 * 1024)).isNull();
    }

    @Test void actualMysqlGuardProtectsCompressedByteAndNotOriginalText() {
        byte[] frame = java.util.HexFormat.of().parseHex(MYSQL_GOLDENS[3][1]);
        assertThat(MysqlCompressedText.decode(frame, 100)).isEqualTo("\u001f").doesNotEndWith(" ");
        // A complete zlib stream without its optional storage guard still restores the same byte.
        assertThat(MysqlCompressedText.decode(Arrays.copyOf(frame, frame.length - 1), 100)).isEqualTo("\u001f");
        byte[] wrongGuard = frame.clone(); wrongGuard[wrongGuard.length - 1] = '!';
        assertThatThrownBy(() -> MysqlCompressedText.decode(wrongGuard, 100)).isInstanceOf(IllegalArgumentException.class);
        byte[] doubleGuard = Arrays.copyOf(frame, frame.length + 1); doubleGuard[frame.length] = '.';
        assertThatThrownBy(() -> MysqlCompressedText.decode(doubleGuard, 100)).isInstanceOf(IllegalArgumentException.class);
        byte[] noTerminalCompressedSpace = java.util.HexFormat.of().parseHex(MYSQL_GOLDENS[2][1] + "2E");
        assertThatThrownBy(() -> MysqlCompressedText.decode(noTerminalCompressedSpace, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsTruncatedOrWrongLengthActualMysqlFrames() {
        for (String[] golden : Arrays.copyOf(MYSQL_GOLDENS, 4)) {
            byte[] frame = java.util.HexFormat.of().parseHex(golden[1]);
            int streamEnd = frame.length - (golden[0].equals("1F") ? 1 : 0);
            for (int length = 1; length < streamEnd; length++) {
                byte[] truncated = Arrays.copyOf(frame, length);
                assertThatThrownBy(() -> MysqlCompressedText.decode(truncated, 100)).isInstanceOf(IllegalArgumentException.class);
            }
            for (int declared : new int[]{0, 1_000, Integer.MAX_VALUE, -1}) {
                byte[] wrong = frame.clone(); ByteBuffer.wrap(wrong).order(ByteOrder.LITTLE_ENDIAN).putInt(declared);
                assertThatThrownBy(() -> MysqlCompressedText.decode(wrong, 100)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    static String sha(String text) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }

    static byte[] compress(byte[] raw) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw.length).array());
        var deflater = new Deflater();
        try {
            deflater.setInput(raw); deflater.finish();
            byte[] block = new byte[8192];
            while (!deflater.finished()) out.write(block, 0, deflater.deflate(block));
        } finally { deflater.end(); }
        return out.toByteArray();
    }

    @Test void roundTripsUnicodeAndAcceptsMysqlEmptyAndNull() {
        String raw = "{\"证据\":\"😀 空格 \"}";
        assertThat(MysqlCompressedText.decode(compress(raw.getBytes(StandardCharsets.UTF_8)), 1024)).isEqualTo(raw);
        assertThat(MysqlCompressedText.decode(null, 1024)).isNull();
        assertThat(MysqlCompressedText.decode(new byte[0], 1024)).isEmpty();
    }

    @Test void acceptsOnlyOptionalDotAfterCompleteStream() {
        String raw = "a".repeat(127); // Adler32's final byte is a space for this fixture.
        byte[] bytes = compress(raw.getBytes(StandardCharsets.UTF_8));
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte)' ');
        byte[] suffix = Arrays.copyOf(bytes, bytes.length + 1); suffix[bytes.length] = '.';
        assertThat(MysqlCompressedText.decode(suffix, 200)).isEqualTo(raw);
        suffix[bytes.length] = 'x';
        assertThatThrownBy(() -> MysqlCompressedText.decode(suffix, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MysqlCompressedText.decode(Arrays.copyOf(bytes, bytes.length + 2), 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsCorruptionTruncationAndLengthMismatch() {
        byte[] bytes = compress("evidence".getBytes(StandardCharsets.UTF_8));
        for (int length = 1; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThatThrownBy(() -> MysqlCompressedText.decode(truncated, 100)).isInstanceOf(IllegalArgumentException.class);
        }
        byte[] corrupt = bytes.clone(); corrupt[corrupt.length - 1] ^= 1;
        assertThatThrownBy(() -> MysqlCompressedText.decode(corrupt, 100)).isInstanceOf(IllegalArgumentException.class);
        for (int declared : new int[]{0, 1, 7, 9, Integer.MAX_VALUE, -1}) {
            byte[] wrong = bytes.clone(); ByteBuffer.wrap(wrong).order(ByteOrder.LITTLE_ENDIAN).putInt(declared);
            assertThatThrownBy(() -> MysqlCompressedText.decode(wrong, 100)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsInvalidUtf8AndEnforcesPublisherLimitIncludingExactBoundary() {
        assertThatThrownBy(() -> MysqlCompressedText.decode(compress(new byte[]{(byte)0xc3, 0x28}), 100))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] raw = new byte[16 * 1024 * 1024]; Arrays.fill(raw, (byte)'a');
        assertThat(MysqlCompressedText.decode(compress(raw), raw.length)).hasSize(raw.length);
        assertThatThrownBy(() -> MysqlCompressedText.decode(compress(raw), raw.length - 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void optInUsesSameSelectionAndRejectsChangedBodyWithOldHash() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getDate("day_date")).thenReturn(Date.valueOf("2026-01-01"));
        when(result.getString("view_json")).thenReturn("{}");
        when(result.getLong("view_utf8_bytes")).thenReturn(2L);
        when(result.getBytes("view_compressed")).thenReturn(compress("{}".getBytes(StandardCharsets.UTF_8)));
        when(result.getString("view_hash")).thenReturn(sha("{}"));
        when(result.getString("evidence_hash")).thenReturn(sha("{\"original\":true}"));
        when(result.getBytes("evidence_compressed")).thenReturn(compress("{\"changed\":true}".getBytes(StandardCharsets.UTF_8)));
        var repository = new HourlyCarbonRepository(new JdbcTemplate(source), new ObjectMapper(),
                mock(AuditEvidenceWriter.class), new AuditGovernanceProperties());
        ReflectionTestUtils.setField(repository, "readCompression", true);
        assertThatThrownBy(() -> repository.byDate("B", LocalDate.of(2026, 1, 1), null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("HOURLY_CARBON_INTEGRITY_VIOLATION"));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("COMPRESS(CONVERT(evidence_json USING utf8mb4))",
                "WHERE building_id=? AND day_date=?", "ORDER BY calculation_as_of DESC, revision DESC", "LIMIT 1");
        verify(result, never()).getString("evidence_json");
        verify(result).close(); verify(statement).close(); verify(connection).close();
    }

    @Test void compressedReadReturnsExactlyTheSameVerifiedTextAndView() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var repository = new HourlyCarbonRepository(new JdbcTemplate(source), mapper,
                mock(AuditEvidenceWriter.class), new AuditGovernanceProperties());
        String evidence = "{\"original\":true}";
        var day = LocalDate.of(2026, 1, 1);
        var asOf = java.time.Instant.parse("2026-02-01T00:00:00Z");
        var view = new HourlyCarbonContracts.View("B", day, "Asia/Shanghai", "KG_CO2E",
                "PURCHASED_ELECTRICITY_SCOPE_2", "RAW_CUMULATIVE", "SOURCE_LABELLED", "FORMAL", "MONITORING", "PARTIAL",
                "batch", 1, asOf, asOf, "test", 24, 0, java.math.BigDecimal.ZERO, java.util.List.of(), asOf, sha(evidence));
        String viewJson = ReflectionTestUtils.invokeMethod(repository, "canonicalJson", view);
        when(result.getDate("day_date")).thenReturn(Date.valueOf(day));
        when(result.getString("batch_id")).thenReturn("batch");
        when(result.getString("building_id")).thenReturn("B");
        when(result.getInt("revision")).thenReturn(1);
        when(result.getLong("calculation_as_of")).thenReturn(asOf.toEpochMilli());
        when(result.getString("view_json")).thenReturn(viewJson);
        when(result.getLong("view_utf8_bytes")).thenReturn((long) viewJson.getBytes(StandardCharsets.UTF_8).length);
        when(result.getBytes("view_compressed")).thenReturn(compress(viewJson.getBytes(StandardCharsets.UTF_8)));
        when(result.getString("view_hash")).thenReturn(sha(viewJson));
        when(result.getString("evidence_json")).thenReturn(evidence);
        when(result.getString("evidence_hash")).thenReturn(sha(evidence));
        when(result.getBytes("evidence_compressed")).thenReturn(compress(evidence.getBytes(StandardCharsets.UTF_8)));
        when(result.next()).thenReturn(true, false);
        var raw = repository.byDate("B", day, 1);
        ReflectionTestUtils.setField(repository, "readCompression", true);
        when(result.next()).thenReturn(true, false);
        HourlyCarbonRepository.Stored compressed;
        try (var ignored = CarbonCalculationDeadline.start(java.time.Duration.ofSeconds(30)).bind()) {
            compressed = repository.byDate("B", day, 1);
        }
        assertThat(compressed).isEqualTo(raw);
        verify(connection, times(2)).prepareStatement(contains("AND revision=?"));
        verify(connection).prepareStatement(contains("MAX_EXECUTION_TIME("));
        // Recomputed hashes do not bypass canonical or identity checks after decompression.
        String noncanonical = "{ \"original\": true }";
        when(result.next()).thenReturn(true, false);
        when(result.getBytes("evidence_compressed")).thenReturn(compress(noncanonical.getBytes(StandardCharsets.UTF_8)));
        when(result.getString("evidence_hash")).thenReturn(sha(noncanonical));
        assertIntegrity(() -> repository.byDate("B", day, 1));
        when(result.next()).thenReturn(true, false);
        when(result.getBytes("evidence_compressed")).thenReturn(null);
        assertIntegrity(() -> repository.byDate("B", day, 1));
        when(result.next()).thenReturn(true, false);
        when(result.getBytes("evidence_compressed")).thenReturn(compress(evidence.getBytes(StandardCharsets.UTF_8)));
        when(result.getString("evidence_hash")).thenReturn(sha(evidence));
        when(result.getString("building_id")).thenReturn("OTHER");
        assertIntegrity(() -> repository.byDate("B", day, 1));
        when(statement.executeQuery()).thenThrow(new SQLSyntaxErrorException("COMPRESS unavailable", "42000"));
        assertThatThrownBy(() -> repository.byDate("B", day, 1))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static void assertIntegrity(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo("HOURLY_CARBON_INTEGRITY_VIOLATION"));
    }
}
