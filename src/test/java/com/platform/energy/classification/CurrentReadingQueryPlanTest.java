package com.platform.energy.classification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL shape regression only; real MySQL EXPLAIN remains necessary to verify optimizer behavior. */
class CurrentReadingQueryPlanTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ClassificationRepository repository = new ClassificationRepository(jdbc);
    private final Instant from = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant to = from.plusSeconds(86400);

    @Test void pointReadKeepsReplacementLookupCorrelated() {
        repository.currentApprovedReadings("BUILDING", "POINT", from, to, 2);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("BUILDING"), eq("POINT"),
                eq(to.toEpochMilli()), eq(from.toEpochMilli()), eq(2));
        assertBoundedReplacement(sql.getValue());
        assertThat(sql.getValue()).contains("r.point_id=?");
    }

    @Test void firstPageKeepsReplacementLookupCorrelated() {
        repository.currentApprovedReadingsPage("BUILDING", from, to, null, null, 5000);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("BUILDING"),
                eq(to.toEpochMilli()), eq(from.toEpochMilli()), eq(5000));
        assertBoundedReplacement(sql.getValue());
        assertThat(sql.getValue()).doesNotContain("r.start_inclusive>?");
    }

    @Test void laterPageRetainsCursorAndCorrelatedReplacementLookup() {
        var cursor = from.plusSeconds(3600);
        repository.currentApprovedReadingsPage("BUILDING", from, to, cursor, "FACT", 5000);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("BUILDING"),
                eq(to.toEpochMilli()), eq(from.toEpochMilli()), eq(cursor.toEpochMilli()),
                eq(cursor.toEpochMilli()), eq("FACT"), eq(5000));
        assertBoundedReplacement(sql.getValue());
        assertThat(sql.getValue()).contains("r.start_inclusive>? OR (r.start_inclusive=? AND r.fact_id>?)");
    }

    private void assertBoundedReplacement(String sql) {
        assertThat(sql).contains("NO_SEMIJOIN(@current_replacement)", "QB_NAME(current_replacement)", "JOIN_ORDER(s,sb)",
                "s.building_id=r.building_id", "s.supersedes_fact_id=r.fact_id", "sb.status='APPROVED'",
                "ORDER BY r.start_inclusive,r.fact_id LIMIT ?");
        assertThat(sql).doesNotContain("s.supersedes_fact_id IS NOT NULL");
    }

    @Test void monthlyPageKeepsFullOverlapAndBindsRemainingBudgetToJdbc() throws Exception {
        var creator=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        var setter=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementSetter.class);
        var cursor=from.plusSeconds(3600);
        repository.currentApprovedWindowPage("BUILDING",from,to,cursor,"FACT",1000,()->Duration.ofMillis(7900));
        verify(jdbc).query(creator.capture(),setter.capture(),any(org.springframework.jdbc.core.ResultSetExtractor.class));
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        assertThat(creator.getValue().createPreparedStatement(connection)).isSameAs(statement);
        when(statement.getQueryTimeout()).thenReturn(20);
        setter.getValue().setValues(statement);
        var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("MAX_EXECUTION_TIME(7900)","NO_SEMIJOIN(@window_replacement)","INDEX(r idx_energy_manual_cursor)",
                "JOIN_ORDER(s,sb)","r.start_inclusive<? AND r.end_exclusive>?","OCTET_LENGTH(r.reading_json)<=16384")
                .doesNotContain("r.start_inclusive>=?");
        verify(statement).setQueryTimeout(7);
        verify(statement).setObject(1,"BUILDING");verify(statement).setObject(2,to.toEpochMilli());
        verify(statement).setObject(3,from.toEpochMilli());verify(statement).setObject(4,cursor.toEpochMilli());
        verify(statement).setObject(5,cursor.toEpochMilli());verify(statement).setObject(6,"FACT");verify(statement).setObject(7,1000);
        clearInvocations(statement);when(statement.getQueryTimeout()).thenReturn(2);
        setter.getValue().setValues(statement);verify(statement).setQueryTimeout(2);
    }
    @Test void expiredMonthlyScanNeverIssuesJdbcWork() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->repository.currentApprovedWindowPage("BUILDING",from,to,null,null,1000,()->Duration.ZERO))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void connectionWaitCannotRestartTheRemainingQueryBudget() throws Exception {
        var setter=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementSetter.class);
        var remaining=new java.util.concurrent.atomic.AtomicReference<>(Duration.ofSeconds(30));
        repository.currentApprovedWindowPage("BUILDING",from,to,null,null,1000,remaining::get);
        verify(jdbc).query(any(org.springframework.jdbc.core.PreparedStatementCreator.class),setter.capture(),any(org.springframework.jdbc.core.ResultSetExtractor.class));
        remaining.set(Duration.ZERO);var statement=mock(java.sql.PreparedStatement.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->setter.getValue().setValues(statement))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verifyNoInteractions(statement);
    }
    @Test void serverBudgetUsesLiveMillisecondsAfterConnectionAcquisition() throws Exception {
        var creator=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        var remaining=new java.util.concurrent.atomic.AtomicReference<>(Duration.ofSeconds(30));
        repository.currentApprovedWindowPage("BUILDING",from,to,null,null,1000,remaining::get);
        verify(jdbc).query(creator.capture(),any(org.springframework.jdbc.core.PreparedStatementSetter.class),any(org.springframework.jdbc.core.ResultSetExtractor.class));
        remaining.set(Duration.ofMillis(1234));
        var connection=mock(java.sql.Connection.class);
        creator.getValue().createPreparedStatement(connection);
        var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("MAX_EXECUTION_TIME(1234)").doesNotContain("MAX_EXECUTION_TIME(30000)");
    }
    @Test void exhaustedSubmillisecondBudgetCannotDisableServerLimitWithZero() throws Exception {
        var creator=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        var remaining=new java.util.concurrent.atomic.AtomicReference<>(Duration.ofSeconds(30));
        repository.currentApprovedWindowPage("BUILDING",from,to,null,null,1000,remaining::get);
        verify(jdbc).query(creator.capture(),any(org.springframework.jdbc.core.PreparedStatementSetter.class),any(org.springframework.jdbc.core.ResultSetExtractor.class));
        remaining.set(Duration.ofNanos(999999));var connection=mock(java.sql.Connection.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->creator.getValue().createPreparedStatement(connection))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verifyNoInteractions(connection);
    }

    @Test void metadataPageBoundsRawIndexCandidatesWithoutReadingJsonOrFilteringOutCursorProgress() throws Exception {
        var creator=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        var setter=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementSetter.class);
        var cursor=from.minusSeconds(3600);
        repository.windowCandidatesPage("BUILDING",to,cursor,"CURSOR",1000,()->Duration.ofMillis(3500));
        verify(jdbc).query(creator.capture(),setter.capture(),any(org.springframework.jdbc.core.ResultSetExtractor.class));
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);setter.getValue().setValues(statement);
        var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("MAX_EXECUTION_TIME(3500)","INDEX(r idx_energy_manual_cursor)",
                "r.fact_id,r.start_inclusive,r.end_exclusive","r.building_id=? AND r.start_inclusive<?",
                "r.start_inclusive>? OR (r.start_inclusive=? AND r.fact_id>?)","ORDER BY r.start_inclusive,r.fact_id LIMIT ?")
                .doesNotContain("reading_json","JOIN","end_exclusive>?");
        verify(statement).setObject(1,"BUILDING");verify(statement).setObject(2,to.toEpochMilli());
        verify(statement).setObject(3,cursor.toEpochMilli());verify(statement).setObject(4,cursor.toEpochMilli());
        verify(statement).setObject(5,"CURSOR");verify(statement).setObject(6,1000);
    }
    @Test void payloadBatchIsBoundedByDistinctPrimaryIdsAndKeepsEveryBusinessPredicate() throws Exception {
        var creator=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        var setter=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementSetter.class);
        repository.currentApprovedWindowByIds("BUILDING",from,to,java.util.List.of("A","B"),()->Duration.ofMillis(2500));
        verify(jdbc).query(creator.capture(),setter.capture(),any(org.springframework.jdbc.core.ResultSetExtractor.class));
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);setter.getValue().setValues(statement);
        var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("MAX_EXECUTION_TIME(2500)","INDEX(r PRIMARY)","JOIN_ORDER(r,b)",
                "NO_SEMIJOIN(@id_replacement)","QB_NAME(id_replacement)","JOIN_ORDER(s,sb)",
                "r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?","b.status='APPROVED'",
                "s.building_id=r.building_id AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED'",
                "OCTET_LENGTH(r.reading_json)<=16384","r.fact_id IN (?,?) ORDER BY r.fact_id")
                .doesNotContain("r.start_inclusive>=?");
        verify(statement).setObject(1,"BUILDING");verify(statement).setObject(2,to.toEpochMilli());
        verify(statement).setObject(3,from.toEpochMilli());verify(statement).setObject(4,"A");verify(statement).setObject(5,"B");
    }
    @Test void invalidBatchAndMetadataSizesCannotIssueUnboundedWork() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->repository.windowCandidatesPage("B",to,null,null,1001,()->Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->repository.currentApprovedWindowByIds("B",from,to,java.util.List.of("A","A"),()->Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.currentApprovedWindowByIds("B",from,to,java.util.List.of(),()->Duration.ZERO)).isEmpty();
        verifyNoInteractions(jdbc);
    }


    @Test void overlappingMetadataPreservesHistoricalCrossingRowsNullsCursorAndDeadline() throws Exception {
        var creator=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        var setter=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementSetter.class);
        var cursor=from.minusSeconds(3600);
        repository.overlappingWindowCandidatesPage("BUILDING",from,to,cursor,"CURSOR",1000,()->Duration.ofMillis(3500));
        verify(jdbc).query(creator.capture(),setter.capture(),any(org.springframework.jdbc.core.ResultSetExtractor.class));
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);setter.getValue().setValues(statement);
        var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("MAX_EXECUTION_TIME(3500)","INDEX(r idx_energy_manual_cursor)",
                "r.fact_id,r.start_inclusive,r.end_exclusive","r.building_id=? AND r.start_inclusive<?",
                "AND (r.end_exclusive>? OR r.end_exclusive IS NULL)",
                "r.start_inclusive>? OR (r.start_inclusive=? AND r.fact_id>?)","ORDER BY r.start_inclusive,r.fact_id LIMIT ?")
                .doesNotContain("reading_json","JOIN","r.start_inclusive>=?","r.end_exclusive>r.start_inclusive");
        verify(statement).setObject(1,"BUILDING");verify(statement).setObject(2,to.toEpochMilli());
        verify(statement).setObject(3,from.toEpochMilli());verify(statement).setObject(4,cursor.toEpochMilli());
        verify(statement).setObject(5,cursor.toEpochMilli());verify(statement).setObject(6,"CURSOR");verify(statement).setObject(7,1000);
        verify(statement).setQueryTimeout(3);
    }
}
