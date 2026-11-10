package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.platform.carbon.DynamicElectricityQueryObservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamicElectricityQueryObservationTest {
    @Test void nullJdbcResultsKeepTheirOriginalNullReturnWithoutAnObservationContext() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new DynamicElectricityRepository(jdbc, new ObjectMapper());
        Instant from = Instant.parse("2026-09-01T00:00:00Z"), to = from.plusSeconds(86400);

        assertThat(repository.currentReviewedDayCandidates("B", from, to, null, 1000, () -> Duration.ofSeconds(30))).isNull();
        assertThat(repository.currentReviewedDayBodies("B", from, to, List.of("fact"), () -> Duration.ofSeconds(30))).isNull();

        verify(jdbc, times(2)).query(any(PreparedStatementCreator.class), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void nullJdbcResultsAlsoReturnNullWithContextAndDoNotCreateCountFailures() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new DynamicElectricityRepository(jdbc, new ObjectMapper());
        Instant from = Instant.parse("2026-09-01T00:00:00Z"), to = from.plusSeconds(86400);
        var messages = new ArrayList<String>();
        try (var observation = open(() -> 0, messages::add, "null-source")) {
            assertThat(repository.currentReviewedDayCandidates("B", from, to, null, 1000, () -> Duration.ofSeconds(30))).isNull();
            assertThat(repository.currentReviewedDayBodies("B", from, to, List.of("fact"), () -> Duration.ofSeconds(30))).isNull();
            observation.success();
        }

        assertThat(messages.getLast()).contains("success=true", "CANDIDATE_HEADERS:1/1/0/0",
                "CURRENT_RAW_BODIES:1/1/0/0", "CANDIDATE_ROWS:0", "CURRENT_RAW_ROWS:0");
        verify(jdbc, times(2)).query(any(PreparedStatementCreator.class), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void rowCountDoesNotEvaluateCollectionSizeOutsideAnObservationContext() {
        var rows = mock(Collection.class);
        countRows(Count.CANDIDATE_ROWS, null);
        countRows(Count.CANDIDATE_ROWS, rows);
        verifyNoInteractions(rows);
    }

    @Test void recordsEntryBeforeAnUnfinishedOperationAndDoesNotCreateADeadline() {
        var nanos = new AtomicLong();
        var messages = new ArrayList<String>();
        try (var observation = open(nanos::get, messages::add, "trace-123")) {
            String value = observe(Phase.CANDIDATE_HEADERS, () -> {
                assertThat(messages.getLast()).contains("event=phase_begin", "traceId=trace-123",
                        "CANDIDATE_HEADERS:1/0/0/0");
                nanos.set(Duration.ofSeconds(121).toNanos());
                return "unchanged";
            });
            assertThat(value).isEqualTo("unchanged");
            observation.success();
        }
        assertThat(messages.getLast()).contains("event=request_end", "success=true",
                "CANDIDATE_HEADERS:1/1/0/121000");
    }

    @Test void distinguishesStartedCompletedFailedAndNestedInclusiveTime() {
        var nanos = new AtomicLong();
        var messages = new ArrayList<String>();
        var expected = new IllegalStateException("PRIVATE_PAYLOAD_MUST_NOT_BE_LOGGED");
        try (var ignored = open(nanos::get, messages::add, "trace")) {
            assertThatThrownBy(() -> observe(Phase.STORED_RESULTS, () -> {
                nanos.addAndGet(2_000_000);
                observe(Phase.STORED_BODY, () -> {
                    nanos.addAndGet(3_000_000);
                    count(Count.STORED_ROWS, 1);
                    return null;
                });
                throw expected;
            })).isSameAs(expected);
        }
        assertThat(messages.getLast()).contains("success=false", "timing=inclusive_not_additive",
                "STORED_RESULTS:1/0/1/5", "STORED_BODY:1/1/0/3", "STORED_ROWS:1")
                .doesNotContain(expected.getMessage());
    }

    @Test void capsAllFirstStageEventsProgressAndFinalSummaryAtThirtyTwo() {
        var nanos = new AtomicLong();
        var messages = new ArrayList<String>();
        try (var observation = open(nanos::get, messages::add, "trace")) {
            for (var phase : Phase.values()) observe(phase, () -> null);
            for (int i = 0; i < 1_000; i++) {
                nanos.addAndGet(PROGRESS_NANOS);
                observe(Phase.FACTORS, () -> null);
            }
            observation.success();
        }
        assertThat(messages).hasSize(MAX_EVENTS);
        assertThat(messages.getLast()).contains("event=request_end", "FACTORS:1001/1001/0/0");
        assertThat(messages).allSatisfy(message -> assertThat(message.length()).isLessThan(4096));
    }

    @Test void frequentOperationsDoNotProducePerRowProgress() {
        var nanos = new AtomicLong();
        var messages = new ArrayList<String>();
        try (var observation = open(nanos::get, messages::add, "trace")) {
            for (int i = 0; i < 1_000; i++) {
                nanos.addAndGet(1_000_000);
                observe(Phase.STORED_BODY, () -> null);
            }
            observation.success();
        }
        assertThat(messages).hasSize(4);
        assertThat(messages).noneMatch(message -> message.contains("event=progress"));
    }

    @Test void loggerFailureCannotReplaceResultsOrBusinessFailures() {
        var expected = new IllegalStateException("business");
        assertThatCode(() -> {
            try (var ignored = open(() -> 0, message -> { throw new IllegalArgumentException("logger"); }, null)) {
                assertThat(observe(Phase.FACTORS, () -> "value")).isEqualTo("value");
                assertThatThrownBy(() -> observe(Phase.STORED_BODY, () -> { throw expected; })).isSameAs(expected);
            }
        }).doesNotThrowAnyException();
    }

    @Test void closeRemovesTheRequestContextAndIsIdempotent() {
        var messages = new ArrayList<String>();
        var observation = open(() -> 0, messages::add, null);
        observation.close();
        observation.close();
        observe(Phase.STORED_BODY, () -> null);
        count(Count.STORED_ROWS, 5);
        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst()).contains("traceId=unavailable", "queryId=");
    }

    @Test void nestedScopesRestoreTheirOwnCountersAndDoNotLogUntrustedTraceText() {
        var outer = new ArrayList<String>();
        var inner = new ArrayList<String>();
        try (var observation = open(() -> 0, outer::add, "\nsecret trace")) {
            observe(Phase.FACTORS, () -> null);
            try (var nested = open(() -> 0, inner::add, "inner")) {
                observe(Phase.STORED_BODY, () -> null);
                nested.success();
            }
            observe(Phase.FACTORS, () -> null);
            observation.success();
        }
        assertThat(outer.getLast()).contains("traceId=unavailable", "FACTORS:2/2/0/0", "STORED_BODY:0/0/0/0")
                .doesNotContain("secret");
        assertThat(inner.getLast()).contains("traceId=inner", "FACTORS:0/0/0/0", "STORED_BODY:1/1/0/0");
    }

    @Test void virtualThreadsDoNotShareObservationState() throws InterruptedException {
        var outer = new ArrayList<String>();
        var inner = new ArrayList<String>();
        try (var observation = open(() -> 0, outer::add, "parent")) {
            Thread thread = Thread.ofVirtual().start(() -> {
                // An unrelated worker must not inherit the HTTP request's observation.
                observe(Phase.METADATA, () -> null);
                try (var nested = open(() -> 0, inner::add, "child")) {
                    observe(Phase.FACTORS, () -> null);
                    nested.success();
                }
            });
            thread.join();
            observation.success();
        }
        assertThat(outer).hasSize(2);
        assertThat(outer.getLast()).contains("METADATA:0/0/0/0", "FACTORS:0/0/0/0");
        assertThat(inner.getLast()).contains("FACTORS:1/1/0/0");
    }
}
