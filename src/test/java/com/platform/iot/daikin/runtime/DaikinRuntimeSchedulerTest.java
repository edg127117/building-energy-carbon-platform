package com.platform.iot.daikin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.platform.iot.daikin.runtime.DaikinRuntimeClientProvider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaikinRuntimeSchedulerTest {
    private static final String SOURCE = "source-A";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private MutableClock clock;
    private DaikinRuntimeProperties properties;
    private DaikinMonitoringTargets targets;
    private DaikinMonitoringTargets.Target target;
    private Client client;
    private DaikinRuntimeStore store;
    private DaikinRuntimeScheduler scheduler;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:runtime-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("daikin-runtime-test.sql"),
                new ClassPathResource("daikin-monitoring-state-test.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE biz_daikin_source(source_id VARCHAR(200) PRIMARY KEY)");
        jdbc.update("INSERT INTO biz_daikin_source VALUES (?)", SOURCE);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        clock = new MutableClock(Instant.parse("2026-09-16T01:00:00Z"));
        properties = new DaikinRuntimeProperties();
        properties.setEnabled(true);
        properties.setJobsPerSource(1);
        targets = mock(DaikinMonitoringTargets.class);
        target = new DaikinMonitoringTargets.Target(new DaikinDeviceKey(SOURCE, "site", "controller", DaikinDeviceKey.Kind.INDOOR, "001"),
                "pending", "identity", "manufacturer-identity", "DAIKIN_INDOOR", "equipment", "equip-code", "building", "room", "group", 1);
        when(targets.forSource(SOURCE)).thenReturn(List.of(target));
        when(targets.find(target.pendingId())).thenReturn(Optional.of(target));
        client = mock(Client.class);
        when(client.semantics()).thenReturn(new Semantics("confirmed-v1", "hour", ZONE));
        when(client.read(anyString(), any(), any(), any())).thenReturn(batch("0", true));
        store = new DaikinRuntimeStore(jdbc, new ObjectMapper());
        scheduler = create();
    }

    private DaikinRuntimeScheduler create() {
        return new DaikinRuntimeScheduler(jdbc, transaction, properties, Optional.of(source -> Optional.of(client)), targets, store, clock);
    }

    @AfterEach
    void close() { scheduler.close(); }

    @Test
    void disabledAndMissingProviderNeverPlanOrCallManufacturer() {
        properties.setEnabled(false);
        scheduler.runSource(SOURCE);
        verify(client, never()).read(anyString(), any(), any(), any());
        assertThat(count("biz_daikin_runtime_job")).isZero();
        properties.setEnabled(true);
        var withoutProvider = new DaikinRuntimeScheduler(jdbc, transaction, properties, Optional.empty(), targets, store, clock);
        try {
            withoutProvider.runSource(SOURCE);
            assertThat(count("biz_daikin_runtime_job")).isZero();
        } finally { withoutProvider.close(); }
    }

    @Test
    void scheduleIsThreeAmShanghaiAndCalendarPeriodsRespectManufacturerTimezone() {
        assertThat(DaikinRuntimeScheduler.latestSchedule(Instant.parse("2026-09-15T18:59:59Z").toEpochMilli()))
                .isEqualTo(Instant.parse("2026-09-14T19:00:00Z").toEpochMilli());
        assertThat(DaikinRuntimeScheduler.latestSchedule(Instant.parse("2026-09-15T19:00:00Z").toEpochMilli()))
                .isEqualTo(Instant.parse("2026-09-15T19:00:00Z").toEpochMilli());
        var dst = new DaikinRuntimePeriod(DaikinRuntimePeriod.Granularity.DAY, LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"));
        assertThat(dst.endMillis() - dst.startMillis()).isEqualTo(Duration.ofHours(23).toMillis());
    }

    @Test
    void firstPlanIsBoundedNewestFirstAndStoresRealZeroSeparatelyFromMissing() {
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_job")).isBetween(365L, 400L);
        assertThat(jdbc.queryForObject("SELECT MIN(period_end_ms) FROM biz_daikin_runtime_job", Long.class))
                .isGreaterThanOrEqualTo(clock.millis() - DaikinRuntimeScheduler.YEAR_MS);
        assertThat(jdbc.queryForObject("SELECT metrics_json FROM biz_daikin_runtime_value", String.class)).isEqualTo("{\"totalRuntime\":0}");
        assertThat(jdbc.queryForObject("SELECT ownership_verified FROM biz_daikin_runtime_value", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT period_start_ms FROM biz_daikin_runtime_value", Long.class))
                .isEqualTo(LocalDate.of(2026, 9, 15).atStartOfDay(ZONE).toInstant().toEpochMilli());
        assertThat(count("biz_daikin_runtime_revision")).isEqualTo(1);
    }

    @Test
    void failureKeepsPreviousZeroAndRaisesSeparateExceptionAfterBoundedAttempts() {
        scheduler.runSource(SOURCE);
        keepCompletedJobAndRequeue();
        when(client.read(anyString(), any(), any(), any())).thenThrow(new IllegalStateException("do-not-store-response-secrets"));
        for (int attempt = 0; attempt < 3; attempt++) {
            scheduler.runSource(SOURCE);
            clock.advance(Duration.ofMinutes(5));
        }
        assertThat(jdbc.queryForObject("SELECT metrics_json FROM biz_daikin_runtime_value", String.class)).isEqualTo("{\"totalRuntime\":0}");
        assertThat(jdbc.queryForObject("SELECT last_attempt_status FROM biz_daikin_runtime_value", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM biz_daikin_runtime_job", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT exception_type FROM biz_daikin_exception_instance", String.class)).isEqualTo("RUNTIME_FETCH");
        assertThat(count("biz_daikin_runtime_revision")).isEqualTo(1);
        clock.advance(Duration.ofDays(1));
        doReturn(batch("2", true)).when(client).read(anyString(), any(), any(), any());
        properties.setJobsPerSource(10);
        scheduler.runSource(SOURCE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_exception_instance WHERE recovered_at_ms IS NULL", Long.class)).isZero();
    }

    @Test
    void missingAndUnsupportedDoNotFabricateZeroOrRetryPermanentUnsupported() {
        when(client.read(anyString(), any(), any(), any())).thenReturn(new Batch(true, List.of()));
        scheduler.runSource(SOURCE);
        String job = jdbc.queryForObject("SELECT job_id FROM biz_daikin_runtime_job WHERE status='UNSUPPORTED'", String.class);
        assertThat(jdbc.queryForObject("SELECT metrics_json FROM biz_daikin_runtime_value", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_attempt_status FROM biz_daikin_runtime_value", String.class)).isEqualTo("UNSUPPORTED");
        clock.advance(Duration.ofDays(1));
        scheduler.runSource(SOURCE);
        assertThat(jdbc.queryForObject("SELECT status FROM biz_daikin_runtime_job WHERE job_id=?", String.class, job)).isEqualTo("UNSUPPORTED");
        when(client.read(anyString(), any(), any(), any())).thenReturn(new Batch(false, List.of()));
        scheduler.runSource(SOURCE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_runtime_value WHERE metrics_json IS NULL AND last_attempt_status='MISSING'", Long.class)).isEqualTo(1);
    }

    @Test
    void revisionsAreIdempotentAndDayMonthYearStayIndependent() {
        scheduler.runSource(SOURCE);
        keepCompletedJobAndRequeue();
        when(client.read(anyString(), any(), any(), any())).thenReturn(batch("0.00", true));
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_revision")).isEqualTo(1);
        requeue();
        when(client.read(anyString(), any(), any(), any())).thenReturn(batch("2.5", true));
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_revision")).isEqualTo(2);
        var month = new DaikinRuntimePeriod(DaikinRuntimePeriod.Granularity.MONTH, LocalDate.of(2026, 9, 1), ZONE);
        var year = new DaikinRuntimePeriod(DaikinRuntimePeriod.Granularity.YEAR, LocalDate.of(2026, 1, 1), ZONE);
        transaction.executeWithoutResult(status -> {
            store.save(target, month, "hour", batch("100", true).readings().getFirst(), "PRESENT", clock.millis());
            store.save(target, year, "hour", batch("1000", true).readings().getFirst(), "PRESENT", clock.millis());
        });
        assertThat(count("biz_daikin_runtime_value")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_runtime_value WHERE granularity IN ('MONTH','YEAR') AND period_complete=0", Long.class)).isEqualTo(2);
    }

    @Test
    void expiredLeaseCannotWriteAndRestartReclaimsSameDurablePeriod() {
        when(client.read(anyString(), any(), any(), any())).thenAnswer(call -> {
            clock.advance(Duration.ofSeconds(properties.getLeaseSeconds() + 1));
            return batch("7", true);
        });
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_value")).isZero();
        jdbc.update("DELETE FROM biz_daikin_runtime_job WHERE status<>'RUNNING'");
        scheduler.close();
        scheduler = create();
        when(client.read(anyString(), any(), any(), any())).thenReturn(batch("8", true));
        scheduler.runSource(SOURCE);
        assertThat(jdbc.queryForObject("SELECT attempts FROM biz_daikin_runtime_job", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT metrics_json FROM biz_daikin_runtime_value", String.class)).contains("8");
    }

    @Test
    void ownershipChangeDuringRequestDiscardsOldSnapshot() {
        when(client.read(anyString(), any(), any(), any())).thenAnswer(call -> {
            when(targets.find(target.pendingId())).thenReturn(Optional.empty());
            return batch("99", true);
        });
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_value")).isZero();
    }

    @Test
    void anotherInstanceLeaseAndOutOfRetentionJobCannotBeOverwritten() {
        when(client.read(anyString(), any(), any(), any())).thenAnswer(call -> {
            jdbc.update("UPDATE biz_daikin_runtime_job SET lease_token='new-owner' WHERE status='RUNNING'");
            return batch("99", true);
        });
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_value")).isZero();
        jdbc.update("DELETE FROM biz_daikin_runtime_job WHERE status<>'RUNNING'");
        jdbc.update("UPDATE biz_daikin_runtime_job SET period_end_ms=?,lease_until_ms=0", clock.millis() - DaikinRuntimeScheduler.YEAR_MS - 1);
        clearInvocations(client);
        scheduler.runSource(SOURCE);
        verify(client, never()).read(anyString(), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT status FROM biz_daikin_runtime_job", String.class)).isEqualTo("EXPIRED");
    }

    @Test
    void semanticChangeRequiresExplicitMigrationAndDoesNotMixUnits() {
        scheduler.runSource(SOURCE);
        clearInvocations(client);
        when(client.semantics()).thenReturn(new Semantics("confirmed-v2", "minute", ZONE));
        assertThatThrownBy(() -> scheduler.runSource(SOURCE)).hasMessageContaining("REQUIRES_MIGRATION");
        verify(client, never()).read(anyString(), any(), any(), any());
    }

    @Test
    void newBindingReopensHistoryButDoesNotOverwriteAnotherIdentity() {
        scheduler.runSource(SOURCE);
        keepCompletedJobAndRequeue();
        scheduler.runSource(SOURCE);
        var second = new DaikinMonitoringTargets.Target(new DaikinDeviceKey(SOURCE, "site", "controller", DaikinDeviceKey.Kind.INDOOR, "002"),
                "pending2", "identity2", "manufacturer-identity2", "DAIKIN_INDOOR", "equipment2", "code2", "building", "room", "group", 1);
        when(targets.forSource(SOURCE)).thenReturn(List.of(target, second));
        when(targets.find(second.pendingId())).thenReturn(Optional.of(second));
        when(client.read(anyString(), any(), any(), any())).thenReturn(new Batch(false, List.of(
                batch("0", true).readings().getFirst(), new Reading(second.key(), Status.PRESENT, Map.of("totalRuntime", BigDecimal.TEN), true))));
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_value")).isEqualTo(2);
    }

    @Test
    void malformedOrDuplicateManufacturerReadingsFailWithoutPartialWrites() {
        assertThatThrownBy(() -> new Reading(target.key(), Status.PRESENT, Map.of("totalRuntime", new BigDecimal("-1")), true))
                .isInstanceOf(IllegalArgumentException.class);
        Reading reading = batch("1", true).readings().getFirst();
        when(client.read(anyString(), any(), any(), any())).thenReturn(new Batch(false, List.of(reading, reading)));
        scheduler.runSource(SOURCE);
        assertThat(count("biz_daikin_runtime_revision")).isZero();
        assertThat(jdbc.queryForObject("SELECT metrics_json FROM biz_daikin_runtime_value", String.class)).isNull();
    }

    private Batch batch(String value, boolean complete) {
        return new Batch(false, List.of(new Reading(target.key(), Status.PRESENT, Map.of("totalRuntime", new BigDecimal(value)), complete)));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void keepCompletedJobAndRequeue() {
        jdbc.update("DELETE FROM biz_daikin_runtime_job WHERE status<>'SUCCEEDED'");
        requeue();
    }
    private void requeue() { jdbc.update("UPDATE biz_daikin_runtime_job SET status='QUEUED',attempts=0,next_attempt_at_ms=0"); }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

}
