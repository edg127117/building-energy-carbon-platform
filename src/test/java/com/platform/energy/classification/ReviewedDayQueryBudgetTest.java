package com.platform.energy.classification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQL/JDBC shape only; MySQL optimizer cost still requires a separately authorized plan measurement. */
class ReviewedDayQueryBudgetTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ClassificationRepository repository=new ClassificationRepository(jdbc);
    private final Instant from=Instant.parse("2026-08-01T16:00:00Z"),to=from.plusSeconds(86400),asOf=to.plusSeconds(86400);

    @Test void exactPrimaryKeysCutoffBodyCaseAndLiveTimeoutBoundEveryBodyBatch() throws Exception {
        var creator=ArgumentCaptor.forClass(PreparedStatementCreator.class);
        var setter=ArgumentCaptor.forClass(PreparedStatementSetter.class);
        repository.approvedWindowByIdsAsOf("B",from,to,asOf,List.of("A","B"),()->Duration.ofMillis(1750));
        verify(jdbc).query(creator.capture(),setter.capture(),any(ResultSetExtractor.class));
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);setter.getValue().setValues(statement);
        var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("MAX_EXECUTION_TIME(1750)","INDEX(r PRIMARY)","JOIN_ORDER(r,b)",
                "NO_SEMIJOIN(@day_asof_replacement)","JOIN_ORDER(s,sb)","INDEX(s idx_energy_manual_supersedes)","r.fact_id IN (?,?)",
                "r.start_inclusive<? AND r.end_exclusive>?",
                "b.created_at<=? AND b.reviewed_at<=?","sb.created_at<=? AND sb.reviewed_at<=?",
                "CASE WHEN OCTET_LENGTH(r.reading_json)<=16384 THEN r.reading_json ELSE NULL END")
                .doesNotContain("r.start_inclusive>=?","COUNT(","SUM(");
        verify(statement).setObject(1,"B");verify(statement).setObject(2,to.toEpochMilli());verify(statement).setObject(3,from.toEpochMilli());
        for(int i=4;i<=7;i++)verify(statement).setObject(i,asOf.toEpochMilli());
        verify(statement).setObject(8,"A");verify(statement).setObject(9,"B");verify(statement).setQueryTimeout(1);
        assertThat(ReviewedDayReadings.DEFAULT_LIMITS.bodyPageSize()*16384).isEqualTo(4*1024*1024);
    }

    @Test void oversizedOrDuplicateBatchesCannotReachJdbcAndExpiredBudgetCannotRestart() {
        var ids=java.util.stream.IntStream.range(0,257).mapToObj(Integer::toString).toList();
        assertThatThrownBy(()->repository.approvedWindowByIdsAsOf("B",from,to,asOf,ids,()->Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.approvedWindowByIdsAsOf("B",from,to,asOf,List.of("A","A"),()->Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.approvedWindowByIdsAsOf("B",from,to,asOf,List.of("A"),()->Duration.ZERO)).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verifyNoInteractions(jdbc);
    }
}
