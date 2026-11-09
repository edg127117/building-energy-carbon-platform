package com.platform.carbon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicElectricityStoredJdbcTimingTest {
    @Test void oneBatchCountsBoundariesOnceAndUsesAggregateOnly() {
        var logs = new ArrayList<String>(); var time = new AtomicLong();
        try (var scope = DynamicElectricityQueryObservation.open(time::get, logs::add, "offline")) {
            var t = DynamicElectricityQueryObservation.storedJdbcTiming();
            time.set(2_000_000); t.statementReady();
            time.set(7_000_000); t.firstRow(); t.firstRow();
            time.set(19_000_000); t.finish(true); t.finish(true);
        }
        assertThat(logs).hasSize(2);
        assertThat(logs.getLast()).contains("QUERY_ENTRY_TO_STATEMENT_READY:1/1/0/0/2",
                "STATEMENT_READY_TO_FIRST_ROW:1/1/0/0/5");
    }
    @Test void zeroRowsHasSeparateOutcomeNotAFirstRowOrFailure() {
        var logs = new ArrayList<String>(); var time = new AtomicLong();
        try (var scope = DynamicElectricityQueryObservation.open(time::get, logs::add, "offline")) {
            var t = DynamicElectricityQueryObservation.storedJdbcTiming();
            time.set(1_000_000); t.statementReady();
            time.set(4_000_000); t.finish(true);
        }
        assertThat(logs.getLast()).contains("QUERY_ENTRY_TO_STATEMENT_READY:1/1/0/0/1",
                "STATEMENT_READY_TO_FIRST_ROW:1/0/0/1/3");
    }
    @Test void failureBeforeSetterDoesNotStartExecutionBoundary() {
        var logs = new ArrayList<String>(); var time = new AtomicLong();
        try (var scope = DynamicElectricityQueryObservation.open(time::get, logs::add, "offline")) {
            var t = DynamicElectricityQueryObservation.storedJdbcTiming();
            time.set(3_000_000); t.finish(false);
        }
        assertThat(logs.getLast()).contains("QUERY_ENTRY_TO_STATEMENT_READY:1/0/1/0/3",
                "STATEMENT_READY_TO_FIRST_ROW:0/0/0/0/0");
    }
    @Test void failureAfterSetterAndBeforeFirstRowIsNotAnEmptySuccess() {
        var logs = new ArrayList<String>(); var time = new AtomicLong();
        try (var scope = DynamicElectricityQueryObservation.open(time::get, logs::add, "offline")) {
            var t = DynamicElectricityQueryObservation.storedJdbcTiming();
            time.set(2_000_000); t.statementReady();
            time.set(6_000_000); t.finish(false);
        }
        assertThat(logs.getLast()).contains("QUERY_ENTRY_TO_STATEMENT_READY:1/1/0/0/2",
                "STATEMENT_READY_TO_FIRST_ROW:1/0/1/0/4");
    }
    @Test void absenceOfObservationDoesNotCreateCrossRequestState() {
        var t = DynamicElectricityQueryObservation.storedJdbcTiming();
        t.statementReady(); t.firstRow(); t.finish(true);
        var logs = new ArrayList<String>();
        try (var scope = DynamicElectricityQueryObservation.open(System::nanoTime, logs::add, "offline")) { }
        assertThat(logs.getLast()).contains("QUERY_ENTRY_TO_STATEMENT_READY:0/0/0/0/0");
    }
}
