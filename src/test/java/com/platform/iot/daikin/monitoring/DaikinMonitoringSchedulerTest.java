package com.platform.iot.daikin.monitoring;

import com.platform.iot.daikin.catalog.DaikinCatalogClient;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.model.DaikinFieldValue;
import com.platform.iot.daikin.monitoring.state.DaikinMonitoringStateService;
import com.platform.iot.daikin.sync.DaikinCatalogClientProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.platform.iot.daikin.model.DaikinFieldValue.Status.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DaikinMonitoringSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private static final String SOURCE = "source-A";

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private MutableClock clock;
    private DaikinMonitoringProperties properties;
    private DaikinMonitoringTargets targets;
    private DaikinObservationInbox inbox;
    private DaikinMonitoringStateService state;
    private DaikinCatalogClient client;
    private DaikinMonitoringScheduler scheduler;
    private DaikinMonitoringTargets.Target target;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:daikin-monitor-scheduler-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE biz_daikin_source(source_id VARCHAR(200) PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE biz_daikin_monitor_round(
                  source_id VARCHAR(200) PRIMARY KEY,round_id BIGINT NOT NULL,status VARCHAR(20) NOT NULL,
                  attempts INT NOT NULL,lease_token CHAR(36),lease_until BIGINT NOT NULL DEFAULT 0,
                  next_attempt_at BIGINT NOT NULL,completed_at BIGINT,error_code VARCHAR(64),
                  covered_devices INT NOT NULL DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE biz_daikin_monitor_inbox(
                  observation_id CHAR(64) PRIMARY KEY,source_id VARCHAR(200) NOT NULL,pending_id VARCHAR(32) NOT NULL,
                  round_id BIGINT NOT NULL,observed_at BIGINT NOT NULL,target_json CLOB NOT NULL,
                  observation_json CLOB,status VARCHAR(16) NOT NULL,attempts INT NOT NULL DEFAULT 0,
                  next_attempt_at BIGINT NOT NULL DEFAULT 0,lease_token CHAR(36),lease_until BIGINT NOT NULL DEFAULT 0,
                  error_code VARCHAR(64))
                """);
        jdbc.execute("""
                CREATE TABLE biz_daikin_monitoring_target(
                  identity_id VARCHAR(32) PRIMARY KEY,pending_id VARCHAR(32),active TINYINT NOT NULL)
                """);
        jdbc.update("INSERT INTO biz_daikin_source(source_id) VALUES (?)", SOURCE);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(NOW);
        properties = new DaikinMonitoringProperties();
        properties.setEnabled(true);
        properties.setIntervalSeconds(60);
        properties.setLeaseSeconds(30);
        properties.setRetrySeconds(1);
        properties.setMaxAttempts(3);
        targets = mock(DaikinMonitoringTargets.class);
        inbox = mock(DaikinObservationInbox.class);
        state = mock(DaikinMonitoringStateService.class);
        client = mock(DaikinCatalogClient.class);
        target = new DaikinMonitoringTargets.Target(
                new DaikinDeviceKey(SOURCE, "site-1", "controller-1", DaikinDeviceKey.Kind.INDOOR, "unit-1"),
                "pending-1", "identity-1", "DAIKIN-1", "DAIKIN_INDOOR_V2",
                "equipment-1", "IDU1", "BLD001", "SPACE001", "GROUP001", 1);
        when(targets.forSource(SOURCE)).thenReturn(List.of(target));
        when(targets.find(target.pendingId())).thenReturn(Optional.of(target));
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.stop();
    }

    @Test
    void defaultClosedSkipsReplayTargetsAndSourceWork() {
        properties.setEnabled(false);
        scheduler = scheduler(Optional.empty());

        scheduler.tick();
        scheduler.runSource(SOURCE);

        verifyNoInteractions(targets, inbox, state);
        assertThat(roundStatus()).isEmpty();
    }

    @Test
    void retriesSameRoundAndRecordsOnlyOneTerminalFailure() {
        doThrow(new IllegalStateException("upstream unavailable"))
                .when(client).visitPages(anyString(), any(), any(), any());
        scheduler = scheduler(provider());

        scheduler.runSource(SOURCE);
        assertThat(roundStatus()).containsExactly("RETRY_WAIT", "1");
        verify(state, never()).recordFailure(anyString(), anyLong());

        clock.advanceSeconds(1);
        scheduler.runSource(SOURCE);
        assertThat(roundStatus()).containsExactly("RETRY_WAIT", "2");
        verify(state, never()).recordFailure(anyString(), anyLong());

        clock.advanceSeconds(1);
        scheduler.runSource(SOURCE);
        assertThat(roundStatus()).containsExactly("FAILED", "3");
        verify(state, times(1)).recordFailure(SOURCE, NOW.toEpochMilli());
    }

    @Test
    void threeTerminalRoundsAreReportedAsThreeSourceFailures() {
        properties.setMaxAttempts(1);
        doThrow(new IllegalStateException("upstream unavailable"))
                .when(client).visitPages(anyString(), any(), any(), any());
        scheduler = scheduler(provider());

        scheduler.runSource(SOURCE);
        clock.advanceSeconds(60);
        scheduler.runSource(SOURCE);
        clock.advanceSeconds(60);
        scheduler.runSource(SOURCE);

        verify(state).recordFailure(SOURCE, NOW.toEpochMilli());
        verify(state).recordFailure(SOURCE, NOW.plusSeconds(60).toEpochMilli());
        verify(state).recordFailure(SOURCE, NOW.plusSeconds(120).toEpochMilli());
    }

    @Test
    void acceptedEarlierPageRemainsStagedWhenLaterPageFails() {
        properties.setMaxAttempts(1);
        DaikinDeviceObservation observation = observation();
        when(inbox.stage(target, NOW.toEpochMilli(), observation)).thenReturn("observation-1");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<DaikinDevicePageDecoder.Page> accepted = invocation.getArgument(3);
            accepted.accept(new DaikinDevicePageDecoder.Page(1, 2, 1, List.of(observation)));
            throw new IllegalStateException("page two failed");
        }).when(client).visitPages(anyString(), any(), any(), any());
        scheduler = scheduler(provider());

        scheduler.runSource(SOURCE);

        verify(inbox).stage(target, NOW.toEpochMilli(), observation);
        verify(inbox).process("observation-1");
        assertThat(roundStatus()).containsExactly("FAILED", "1");
        verify(state).recordFailure(SOURCE, NOW.toEpochMilli());
    }

    @Test
    void expiredLeaseWorkerCannotFinishRoundOrRecordResult() {
        doAnswer(invocation -> {
            clock.advanceSeconds(31);
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(client).visitPages(anyString(), any(), any(), any());
        scheduler = scheduler(provider());

        scheduler.runSource(SOURCE);

        assertThat(roundStatus()).containsExactly("RUNNING", "1");
        verify(state, never()).recordFailure(anyString(), anyLong());
        verify(state, never()).recordSuccess(anyString(), anyLong());
    }

    @Test
    void staleScanRunsWhileCollectionIsDisabledAndDeactivatesMissingTarget() {
        properties.setEnabled(false);
        jdbc.update("INSERT INTO biz_daikin_monitoring_target(identity_id,pending_id,active) VALUES ('identity-1','pending-1',1)");
        when(targets.find("pending-1")).thenReturn(Optional.empty());
        scheduler = scheduler(Optional.empty());

        scheduler.scanStale();

        verify(state).deactivateTarget("identity-1");
        verify(state).scanStale(org.mockito.ArgumentMatchers.eq(NOW.toEpochMilli()), org.mockito.ArgumentMatchers.eq(500), any());
    }

    private DaikinMonitoringScheduler scheduler(Optional<DaikinCatalogClientProvider> provider) {
        return new DaikinMonitoringScheduler(
                jdbc, transaction, properties, provider, targets, inbox, state, clock);
    }

    private Optional<DaikinCatalogClientProvider> provider() {
        return Optional.of(source -> SOURCE.equals(source) ? Optional.of(client) : Optional.empty());
    }

    private List<String> roundStatus() {
        return jdbc.query("SELECT status,attempts FROM biz_daikin_monitor_round WHERE source_id=?",
                (rs, row) -> List.of(rs.getString(1), Integer.toString(rs.getInt(2))), SOURCE).stream()
                .findFirst().orElse(List.of());
    }

    private DaikinDeviceObservation observation() {
        return new DaikinDeviceObservation(target.key(), "manufacturer-equipment", "site", "indoor",
                clock.instant(), null, Map.of("onOff", new DaikinFieldValue(PRESENT, "\"on\"", "on")));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant current) { this.current = new AtomicReference<>(current); }

        void advanceSeconds(long seconds) { current.updateAndGet(value -> value.plusSeconds(seconds)); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
