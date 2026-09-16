package com.platform.iot.daikin.sync;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.catalog.DaikinCatalogClient;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DaikinDirectorySyncServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private MutableClock clock;
    private DaikinDirectorySyncProperties properties;
    private DaikinDirectorySyncService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:daikin-sync-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY,del_flag TINYINT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE biz_pending_device(
                  pending_id VARCHAR(32) PRIMARY KEY,identity_type VARCHAR(20) NOT NULL,
                  identity_value VARCHAR(100) NOT NULL,profile_code VARCHAR(50) NOT NULL,
                  last_profile_version INT NOT NULL,first_seen_time TIMESTAMP NOT NULL,
                  last_seen_time TIMESTAMP NOT NULL,report_count BIGINT NOT NULL,
                  latest_event_time TIMESTAMP NOT NULL,latest_time_source VARCHAR(20) NOT NULL,
                  latest_metrics_json CLOB NOT NULL,sample_truncated TINYINT NOT NULL,
                  status VARCHAR(20) NOT NULL,bound_identity_id VARCHAR(32),
                  create_time TIMESTAMP NOT NULL,update_time TIMESTAMP NOT NULL,
                  UNIQUE(identity_type,identity_value))
                """);
        new ResourceDatabasePopulator(new ClassPathResource("daikin-schema-test.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("daikin-sync-schema-test.sql")).execute(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(NOW);
        properties = new DaikinDirectorySyncProperties();
        properties.setEnabled(true);
        properties.setLeaseSeconds(60);
        properties.setBaseBackoffSeconds(10);
        properties.setMaxAttempts(2);
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.stopWorker();
    }

    @Test
    void disabledAndMissingProviderRejectRequestButQueryRemainsAvailable() {
        properties.setEnabled(false);
        service = newService(Optional.empty());

        assertThatThrownBy(() -> service.request("source-A", 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("DAIKIN_SYNC_DISABLED"));

        properties.setEnabled(true);
        assertThatThrownBy(() -> service.request("source-A", 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("DAIKIN_SYNC_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void duplicateRequestReturnsOneActiveJobAndSuccessfulRunCommitsBothCatalogs() {
        insertSource();
        DaikinCatalogClient client = client(indoor("in-1"), outdoor("out-1"));
        service = newService(Optional.of(source -> Optional.of(client)));

        DaikinDirectorySyncService.JobView first = service.request("source-A", 7L);
        DaikinDirectorySyncService.JobView duplicate = service.request("source-A", 8L);
        service.runOne(first.jobId());

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThat(service.get("source-A", first.jobId())).satisfies(job -> {
            assertThat(job.status()).isEqualTo("SUCCEEDED");
            assertThat(job.attempts()).isEqualTo(1);
            assertThat(job.errorCode()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_directory", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void secondCatalogFailureRollsBackFirstCatalogAndUsesOnlyFixedErrorCode() {
        insertSource();
        DaikinCatalogClient client = mock(DaikinCatalogClient.class);
        when(client.read(anyString(), any(), any())).thenAnswer(invocation -> {
            Runnable heartbeat = invocation.getArgument(2);
            heartbeat.run();
            DaikinDeviceKey.Kind kind = invocation.getArgument(1);
            return kind == DaikinDeviceKey.Kind.INDOOR
                    ? List.of(indoor("in-1"))
                    : List.of(indoor("wrong-kind"));
        });
        service = newService(Optional.of(source -> Optional.of(client)));

        String jobId = service.request("source-A", 7L).jobId();
        service.runOne(jobId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_directory", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_pending_device", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_catalog_sync", Integer.class)).isZero();
        assertThat(service.get("source-A", jobId).errorCode()).isEqualTo("DAIKIN_SYNC_CATALOG_INVALID");
    }

    @Test
    void retriesWithBoundedBackoffThenFails() {
        insertSource();
        DaikinCatalogClient client = mock(DaikinCatalogClient.class);
        when(client.read(anyString(), any(), any())).thenThrow(new IllegalStateException("secret detail"));
        service = newService(Optional.of(source -> Optional.of(client)));

        String jobId = service.request("source-A", 7L).jobId();
        service.runOne(jobId);
        assertThat(service.get("source-A", jobId)).satisfies(job -> {
            assertThat(job.status()).isEqualTo("RETRY_WAIT");
            assertThat(job.attempts()).isEqualTo(1);
            assertThat(job.errorCode()).isEqualTo("DAIKIN_SYNC_UPSTREAM_FAILED");
        });

        clock.advanceSeconds(10);
        service.runOne(jobId);
        assertThat(service.get("source-A", jobId)).satisfies(job -> {
            assertThat(job.status()).isEqualTo("FAILED");
            assertThat(job.attempts()).isEqualTo(2);
            assertThat(job.errorCode()).isEqualTo("DAIKIN_SYNC_UPSTREAM_FAILED");
        });
    }

    @Test
    void expiredLeaseAtAttemptLimitFailsWithoutMoreUpstreamIo() {
        insertSource();
        AtomicInteger reads = new AtomicInteger();
        DaikinCatalogClient client = mock(DaikinCatalogClient.class);
        when(client.read(anyString(), any(), any())).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return List.of();
        });
        service = newService(Optional.of(source -> Optional.of(client)));
        String jobId = service.request("source-A", 7L).jobId();
        jdbc.update("""
                UPDATE biz_daikin_directory_sync_job
                SET status='RUNNING',attempts=?,fence_token=2,lease_token='expired',lease_until=?
                WHERE job_id=?
                """, properties.getMaxAttempts(), java.sql.Timestamp.valueOf(
                java.time.LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneId.of("Asia/Shanghai"))), jobId);

        service.runOne(jobId);

        assertThat(reads).hasValue(0);
        assertThat(service.get("source-A", jobId)).satisfies(job -> {
            assertThat(job.status()).isEqualTo("FAILED");
            assertThat(job.attempts()).isEqualTo(properties.getMaxAttempts());
            assertThat(job.errorCode()).isEqualTo("DAIKIN_SYNC_LEASE_EXPIRED");
        });
    }

    @Test
    void expiredWorkerCannotCommitAfterNewFenceSucceeds() throws Exception {
        insertSource();
        CountDownLatch firstBlocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DaikinCatalogClient client = mock(DaikinCatalogClient.class);
        when(client.read(anyString(), any(), any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            Runnable heartbeat = invocation.getArgument(2);
            heartbeat.run();
            if (call == 1) {
                firstBlocked.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            }
            DaikinDeviceKey.Kind kind = invocation.getArgument(1);
            return List.of(kind == DaikinDeviceKey.Kind.INDOOR ? indoor("in-1") : outdoor("out-1"));
        });
        service = newService(Optional.of(source -> Optional.of(client)));
        String jobId = service.request("source-A", 7L).jobId();

        Thread stale = Thread.ofPlatform().start(() -> service.runOne(jobId));
        assertThat(firstBlocked.await(5, TimeUnit.SECONDS)).isTrue();
        clock.advanceSeconds(61);
        service.runOne(jobId);
        releaseFirst.countDown();
        stale.join(5_000);

        assertThat(service.get("source-A", jobId)).satisfies(job -> {
            assertThat(job.status()).isEqualTo("SUCCEEDED");
            assertThat(job.attempts()).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_directory", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void unconfiguredSourcesDoNotStarveLaterConfiguredSource() {
        properties.setBatchSize(4);
        DaikinCatalogClient client = client(indoor("in-1"), outdoor("out-1"));
        service = newService(Optional.of(source -> "source-E".equals(source)
                ? Optional.of(client) : Optional.empty()));
        for (char suffix = 'A'; suffix <= 'E'; suffix++) {
            jdbc.update("INSERT INTO biz_daikin_source(source_id,registered_by,create_time) VALUES (?,?,?)",
                    "source-" + suffix, 7L, java.sql.Timestamp.from(NOW));
        }

        service.enqueueScheduledSources();
        service.enqueueScheduledSources();

        assertThat(jdbc.queryForList(
                "SELECT source_id FROM biz_daikin_directory_sync_job", String.class))
                .containsExactly("source-E");
    }

    private DaikinDirectorySyncService newService(Optional<DaikinCatalogClientProvider> provider) {
        DaikinDirectoryService directory = new DaikinDirectoryService(jdbc, transaction);
        return new DaikinDirectorySyncService(jdbc, transaction, directory, properties, provider, clock);
    }

    private DaikinCatalogClient client(DaikinDeviceObservation indoor, DaikinDeviceObservation outdoor) {
        DaikinCatalogClient client = mock(DaikinCatalogClient.class);
        when(client.read(anyString(), any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return List.of(invocation.getArgument(1) == DaikinDeviceKey.Kind.INDOOR ? indoor : outdoor);
        });
        return client;
    }

    private void insertSource() {
        jdbc.update("INSERT INTO biz_daikin_source(source_id,registered_by,create_time) VALUES ('source-A',7,?)",
                java.sql.Timestamp.from(NOW));
    }

    private DaikinDeviceObservation indoor(String unitId) {
        return observation(DaikinDeviceKey.Kind.INDOOR, unitId);
    }

    private DaikinDeviceObservation outdoor(String unitId) {
        return observation(DaikinDeviceKey.Kind.OUTDOOR, unitId);
    }

    private DaikinDeviceObservation observation(DaikinDeviceKey.Kind kind, String unitId) {
        return new DaikinDeviceObservation(new DaikinDeviceKey(
                "source-A", "site-1", "controller-1", kind, unitId),
                "equipment-" + unitId, "厂家项目", "设备" + unitId,
                clock.instant().minusSeconds(1), null, Map.of());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant current) {
            this.current = new AtomicReference<>(current);
        }

        void advanceSeconds(long seconds) {
            current.updateAndGet(value -> value.plusSeconds(seconds));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
