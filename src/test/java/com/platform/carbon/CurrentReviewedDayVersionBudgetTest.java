package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static com.platform.carbon.CurrentReviewedDayVersionRead.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL/JDBC 合约与局部容量降级；不把模拟测试当作 MySQL 执行计划或性能验收。 */
class CurrentReviewedDayVersionBudgetTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    protected final DynamicElectricityRepository repository=spy(new DynamicElectricityRepository(jdbc,new ObjectMapper()));
    private final java.util.concurrent.atomic.AtomicLong nanos=new java.util.concurrent.atomic.AtomicLong();
    private static final Instant FROM=Instant.parse("2026-08-31T00:00:00Z"),TO=FROM.plusSeconds(86400);
    private static final Supplier<Duration> BUDGET=()->Duration.ofMillis(1750);

    @BeforeEach void controlledStatementClock() {
        doAnswer(call->statementBudget(call.getArgument(0),nanos::get)).when(repository).currentReviewedDayStatementBudget(any());
    }

    @Test void bodyQueriesUseExactPrimaryKeysCurrentPredicatesAndBoundedPayloadWithoutCutoffs() throws Exception {
        repository.currentReviewedDayBodies("B",FROM,TO,List.of("a","B"),BUDGET);
        var captured=capture(0);
        assertThat(captured.sql()).contains("MAX_EXECUTION_TIME(1750)","INDEX(r PRIMARY)","JOIN_ORDER(r,b)",
                "NO_SEMIJOIN(@dynamic_day_manual_replacement)","QB_NAME(dynamic_day_manual_replacement)",
                "JOIN_ORDER(s,sb)","r.fact_id IN (?,?)","ORDER BY r.fact_id",
                "r.start_inclusive<? AND r.end_exclusive>?","s.supersedes_fact_id IS NOT NULL",
                "s.building_id=r.building_id","s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED'",
                "b.batch_id=r.batch_id AND b.building_id=r.building_id",
                "sb.batch_id=s.batch_id AND sb.building_id=s.building_id",
                "CASE WHEN OCTET_LENGTH(r.reading_json)<=16384 THEN r.reading_json ELSE NULL END")
                .doesNotContain("created_at<=","reviewed_at<=","start_inclusive>=","FOR UPDATE");
        verify(captured.statement()).setObject(1,"B");verify(captured.statement()).setObject(2,TO.toEpochMilli());
        verify(captured.statement()).setObject(3,FROM.toEpochMilli());
        verify(captured.statement()).setObject(4,"a");verify(captured.statement()).setObject(5,"B");
        verify(captured.statement()).setQueryTimeout(1);
        assertThat(DEFAULT_LIMITS.bodyPageSize()*DEFAULT_LIMITS.maximumRowBytes()).isEqualTo(4*1024*1024);
    }

    @Test void metadataIsNarrowAndUsesDatabaseKeysetOrderWithBothOverlapPredicates() throws Exception {
        repository.currentReviewedDayCandidates("B",FROM,TO,"a",1000,BUDGET);
        var captured=capture(0);
        assertThat(captured.sql()).contains("MAX_EXECUTION_TIME(1750)","INDEX(r idx_energy_manual_point_period)",
                "r.fact_id,r.start_inclusive,r.end_exclusive","r.start_inclusive<? AND r.end_exclusive>?",
                "r.fact_id>?","ORDER BY r.fact_id LIMIT ?")
                .doesNotContain("reading_json","normalized_quantity","JOIN","OFFSET","start_inclusive>=");
        verify(captured.statement()).setObject(4,"a");verify(captured.statement()).setObject(5,1000);
    }

    @Test void liveTimeoutNeverEnlargesAnExistingSmallerTimeout() throws Exception {
        repository.currentReviewedDayCandidates("B",FROM,TO,null,1000,()->Duration.ofSeconds(30));
        var captured=capture(2);
        assertThat(captured.sql()).contains("MAX_EXECUTION_TIME(30000)");
        verify(captured.statement()).setQueryTimeout(2);
    }

    @Test void eachStatementGetsFreshProtectionAfterFortyFiveSecondsOrHoursBetweenQueries() throws Exception {
        for(long gapSeconds:new long[]{0,45,121,3600}) {
            nanos.addAndGet(Duration.ofSeconds(gapSeconds).toNanos());
            repository.currentReviewedDayCandidates("B",FROM,TO,null,1000,()->Duration.ofDays(1));
            var captured=capture(0);
            assertThat(captured.sql()).contains("MAX_EXECUTION_TIME(30000)");
            verify(captured.statement()).setQueryTimeout(30);
            clearInvocations(jdbc);
        }
    }

    @Test void oneStatementGuardDecaysAcrossPrepareAndExecution() throws Exception {
        repository.currentReviewedDayCandidates("B",FROM,TO,null,1000,()->Duration.ofDays(1));
        var captured=capture(0,Duration.ofSeconds(7),Duration.ofSeconds(8));
        assertThat(captured.sql()).contains("MAX_EXECUTION_TIME(23000)");
        verify(captured.statement()).setQueryTimeout(15);
    }

    @Test void aRealShorterCallerDeadlineKeepsShrinkingAndCannotBeEnlargedByTheGuard() throws Exception {
        long callerDeadline=nanos.get()+Duration.ofSeconds(10).toNanos();
        Supplier<Duration> remaining=()->Duration.ofNanos(Math.max(0,callerDeadline-nanos.get()));
        repository.currentReviewedDayCandidates("B",FROM,TO,null,1000,remaining);
        var captured=capture(0,Duration.ofSeconds(2),Duration.ofSeconds(3));
        assertThat(captured.sql()).contains("MAX_EXECUTION_TIME(8000)");
        verify(captured.statement()).setQueryTimeout(5);
    }

    @Test void aStatementKeepsItsInitialCapEvenIfTheSuppliedDeadlineLaterIncreases() {
        var caller=new java.util.concurrent.atomic.AtomicReference<>(Duration.ofSeconds(5));
        var statement=statementBudget(caller::get,nanos::get);
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());caller.set(Duration.ofHours(1));
        assertThat(statement.get()).isEqualTo(Duration.ofSeconds(4));
        nanos.addAndGet(Duration.ofSeconds(4).toNanos());
        assertThat(statement.get()).isEqualTo(Duration.ZERO);
        assertThat(statementBudget(caller::get,nanos::get).get()).isEqualTo(MAX_STATEMENT_TIME);
    }

    @Test void subsecondBudgetAfterPrepareStopsBeforeExecutionWithoutFallback() throws Exception {
        var statement=executionFixture(Duration.ofSeconds(1),Duration.ZERO);
        assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET))
                .isInstanceOf(QueryTimeoutException.class).hasMessageContaining("before execution");
        verify(statement,never()).setQueryTimeout(anyInt());
        verify(statement,never()).executeQuery();
        verify(repository,never()).electricityVersion(any(),any(),any(),any());
        verify(jdbc,times(1)).query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class));
    }

    @Test void delayBeforeConnectionCallbackCannotPrepareExecuteOrReturnUnavailable() throws Exception {
        for(Duration delay:List.of(Duration.ofSeconds(30),Duration.ofSeconds(31),
                Duration.ofMillis(29250),Duration.ofNanos(29_999_500_000L))) {
            var fixture=queryFixture(delay,Duration.ZERO,Duration.ZERO);
            assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,()->Duration.ofDays(1)))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(fixture.connection(),never()).prepareStatement(anyString());
            verify(fixture.statement(),never()).setObject(anyInt(),any());
            verify(fixture.statement(),never()).executeQuery();
            verify(repository,never()).electricityVersion(any(),any(),any(),any());
            verify(jdbc,times(1)).query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class));
            clearInvocations(jdbc);
        }
    }

    @Test void invalidOrExpiredBudgetsAlwaysThrowBeforeAnyQuery() {
        for(Duration left:Arrays.asList(null,Duration.ZERO,Duration.ofMillis(-1),Duration.ofNanos(500)))
            assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,()->left))
                    .isInstanceOf(QueryTimeoutException.class);
        verifyNoInteractions(jdbc);
        verify(repository,never()).electricityVersion(any(),any(),any(),any());
    }

    @Test void interruptionThrowsAndPreservesTheInterruptFlagWithoutFallback() {
        boolean interrupted=Thread.currentThread().isInterrupted();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET))
                    .isInstanceOf(QueryTimeoutException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            if(!interrupted)Thread.interrupted();
        }
        verifyNoInteractions(jdbc);
        verify(repository,never()).electricityVersion(any(),any(),any(),any());
    }

    @Test void bindingTimeCountsAndSubsecondSubmillisecondZeroOrNegativeRemainingNeverExecutes() throws Exception {
        // Four metadata parameters leave 750 ms, 0.5 ms, zero, or an expired statement guard.
        for(long bindingNanos:new long[]{250_000_000L,437_375_000L,437_500_000L,500_000_000L}) {
            var statement=executionFixture(Duration.ZERO,Duration.ofNanos(bindingNanos));
            assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(statement,times(4)).setObject(anyInt(),any());
            verify(statement,never()).setQueryTimeout(anyInt());
            verify(statement,never()).executeQuery();
            verify(repository,never()).electricityVersion(any(),any(),any(),any());
            verify(jdbc,times(1)).query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class));
            clearInvocations(jdbc);
        }
    }

    @Test void aCallerBudgetExpiringDuringBindingIsAnExecutionTimeoutNotUnavailable() throws Exception {
        long callerDeadline=nanos.get()+Duration.ofMillis(1750).toNanos();
        Supplier<Duration> remaining=()->Duration.ofNanos(Math.max(0,callerDeadline-nanos.get()));
        var statement=executionFixture(Duration.ZERO,Duration.ofMillis(500));
        assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,remaining))
                .isInstanceOf(QueryTimeoutException.class).hasMessageContaining("expired");
        verify(statement,times(4)).setObject(anyInt(),any());
        verify(statement,never()).executeQuery();
        verify(repository,never()).electricityVersion(any(),any(),any(),any());
    }

    @Test void executionGuardPreservesRealCallerExceptionsWithoutConvertingThem() throws Exception {
        var expected=new org.springframework.transaction.TransactionTimedOutException("caller transaction expired");
        Supplier<Duration> remaining=()->{
            if(nanos.get()>=Duration.ofSeconds(1).toNanos())throw expected;
            return Duration.ofMillis(1750);
        };
        var statement=executionFixture(Duration.ofSeconds(1),Duration.ZERO);
        assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,remaining)).isSameAs(expected);
        verify(statement,never()).executeQuery();
        verify(repository,never()).electricityVersion(any(),any(),any(),any());
    }

    @Test void exactlyOneSecondStillUsesTheUnroundedOneSecondJdbcTimeout() throws Exception {
        var statement=executionFixture(Duration.ofMillis(750),Duration.ZERO);
        assertThat(repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET)).isPresent();
        verify(statement).setQueryTimeout(1);
        verify(statement).executeQuery();
    }

    @Test void invalidBatchAndPageBoundsCannotReachJdbc() {
        assertThatThrownBy(()->repository.currentReviewedDayBodies("B",FROM,TO,
                java.util.stream.IntStream.range(0,repository.currentReviewedDayLimits().bodyPageSize()+1).mapToObj(Integer::toString).toList(),BUDGET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.currentReviewedDayBodies("B",FROM,TO,List.of("a","a"),BUDGET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.currentReviewedDayCandidates("B",FROM,TO,null,repository.currentReviewedDayLimits().metadataPageSize()+1,BUDGET))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void databaseTimeoutAndSqlFailureAreNeverConvertedToUnavailableOrRetried() {
        when(jdbc.query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class)))
                .thenThrow(new QueryTimeoutException("driver timed out"));
        assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET))
                .isInstanceOf(QueryTimeoutException.class).hasMessageContaining("driver timed out");
        verify(jdbc,times(1)).query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class));
        reset(jdbc);
        when(jdbc.query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection failed"));
        assertThatThrownBy(()->repository.loadCurrentReviewedDay("B",FROM,TO,BUDGET))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(jdbc,times(1)).query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class));
    }

    @Test void integrityMismatchesAndDuplicateOrReorderedBodiesMustFailRatherThanFallback() {
        var mocked=mock(DynamicElectricityRepository.class);
        var first=new Candidate("a",FROM.toEpochMilli(),TO.toEpochMilli());
        var second=new Candidate("B",FROM.toEpochMilli(),TO.toEpochMilli());
        when(mocked.currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any())).thenReturn(List.of(first,second));
        when(mocked.currentReviewedDayBodies(any(),any(),any(),any(),any())).thenReturn(List.of(fact(second),fact(first)));
        assertThatThrownBy(()->read(mocked,"B",FROM,TO,BUDGET,repository.currentReviewedDayLimits())).isInstanceOf(IllegalStateException.class);
        when(mocked.currentReviewedDayBodies(any(),any(),any(),any(),any())).thenReturn(List.of(fact(first),fact(first)));
        assertThatThrownBy(()->read(mocked,"B",FROM,TO,BUDGET,repository.currentReviewedDayLimits())).isInstanceOf(IllegalStateException.class);
        when(mocked.currentReviewedDayBodies(any(),any(),any(),any(),any())).thenReturn(List.of(fact(
                new Candidate("a",FROM.plusSeconds(1).toEpochMilli(),TO.toEpochMilli()))));
        assertThatThrownBy(()->read(mocked,"B",FROM,TO,BUDGET,repository.currentReviewedDayLimits())).isInstanceOf(IllegalStateException.class);
        when(mocked.currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any())).thenReturn(List.of(first,first));
        assertThatThrownBy(()->read(mocked,"B",FROM,TO,BUDGET,repository.currentReviewedDayLimits())).isInstanceOf(IllegalStateException.class);
    }

    @Test void completedPartialReadThrowsWhenTheSuppliedCallerBudgetExpires() {
        var mocked=mock(DynamicElectricityRepository.class);
        var row=new Candidate("a",FROM.toEpochMilli(),TO.toEpochMilli());
        when(mocked.currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any())).thenReturn(List.of(row));
        when(mocked.currentReviewedDayBodies(any(),any(),any(),any(),any())).thenReturn(List.of(fact(row)));
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->read(mocked,"B",FROM,TO,()->checks.incrementAndGet()<=4?Duration.ofSeconds(1):Duration.ZERO,repository.currentReviewedDayLimits()))
                .isInstanceOf(QueryTimeoutException.class);
        verify(mocked,times(1)).currentReviewedDayBodies(any(),any(),any(),any(),any());
    }

    private Fact fact(Candidate candidate) {
        return new Fact(candidate,Map.of("fact_id",candidate.id(),"point_id","P","reading_json","{}",
                "normalized_quantity",java.math.BigDecimal.ONE,"revision",1,"reviewed_at",1L),2L);
    }

    private java.sql.PreparedStatement executionFixture(Duration afterPrepare,Duration eachBinding) throws Exception {
        return queryFixture(Duration.ZERO,afterPrepare,eachBinding).statement();
    }

    private QueryFixture queryFixture(Duration beforeCallback,Duration afterPrepare,Duration eachBinding) throws Exception {
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        doAnswer(call->{nanos.addAndGet(eachBinding.toNanos());return null;}).when(statement).setObject(anyInt(),any());
        doAnswer(call->{
            PreparedStatementCreator creator=call.getArgument(0);PreparedStatementSetter setter=call.getArgument(1);
            nanos.addAndGet(beforeCallback.toNanos());
            var prepared=creator.createPreparedStatement(connection);
            nanos.addAndGet(afterPrepare.toNanos());setter.setValues(prepared);
            prepared.executeQuery();
            return List.of();
        }).when(jdbc).query(any(PreparedStatementCreator.class),any(PreparedStatementSetter.class),any(ResultSetExtractor.class));
        return new QueryFixture(connection,statement);
    }

    private record QueryFixture(java.sql.Connection connection,java.sql.PreparedStatement statement) { }

    private Captured capture(int existingTimeout) throws Exception {
        return capture(existingTimeout,Duration.ZERO,Duration.ZERO);
    }

    private Captured capture(int existingTimeout,Duration beforePrepare,Duration beforeExecution) throws Exception {
        var creator=ArgumentCaptor.forClass(PreparedStatementCreator.class);
        var setter=ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).query(creator.capture(),setter.capture(),any(ResultSetExtractor.class));
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.getQueryTimeout()).thenReturn(existingTimeout);
        nanos.addAndGet(beforePrepare.toNanos());creator.getValue().createPreparedStatement(connection);
        nanos.addAndGet(beforeExecution.toNanos());setter.getValue().setValues(statement);
        var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        return new Captured(sql.getValue(),statement);
    }

    private record Captured(String sql,java.sql.PreparedStatement statement) { }
}
