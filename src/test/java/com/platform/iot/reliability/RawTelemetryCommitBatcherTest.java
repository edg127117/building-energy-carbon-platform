package com.platform.iot.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class RawTelemetryCommitBatcherTest {
    private JdbcTemplate jdbc;
    private RawTelemetryCodec codec;

    @BeforeEach void setup() {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:batcher-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-test.sql"),new ClassPathResource("telemetry-conflict-schema-test.sql")).execute(dataSource);
        jdbc=new JdbcTemplate(dataSource);codec=new RawTelemetryCodec(new ObjectMapper());
        jdbc.execute("CREATE TABLE batch_probe(point_key VARCHAR(32) PRIMARY KEY,marker VARCHAR(32) NOT NULL)");
    }

    @Test void executeReturnsOnlyAfterTheOuterTransactionCommits() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try(var batcher=new RawTelemetryCommitBatcher(jdbc)) {
            var result=pool.submit(()->batcher.execute("P1",()->{
                jdbc.update("INSERT INTO batch_probe VALUES ('P1','written')");
                entered.countDown();await(release);
                return "committed";
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_probe",Integer.class)).isZero();
            release.countDown();
            assertThat(result.get(10,TimeUnit.SECONDS)).isEqualTo("committed");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_probe",Integer.class)).isEqualTo(1);
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void oneSavedPointFailureRollsBackOnlyThatItemWhileItsBatchPeerCommits() throws Exception {
        var trackingSource=new CommitObservingDataSource(jdbc.getDataSource(),false);
        var batchJdbc=new JdbcTemplate(trackingSource);var pool=Executors.newFixedThreadPool(2);
        var firstPulled=new CountDownLatch(1);var releaseDrain=new CountDownLatch(1);var gate=new AtomicBoolean(true);
        try(var batcher=new RawTelemetryCommitBatcher(batchJdbc,()->holdFirstDrain(gate,firstPulled,releaseDrain))) {
            try {
                var calls=concurrently(pool,
                        ()->batcher.execute(RawTelemetryCommitBatcher.Stage.RESERVE,"A",()->{
                            batchJdbc.update("INSERT INTO batch_probe VALUES ('A','failed')");
                            throw new IllegalStateException("item failure");
                        }),
                        ()->batcher.execute(RawTelemetryCommitBatcher.Stage.RESERVE,"B",()->{
                            batchJdbc.update("INSERT INTO batch_probe VALUES ('B','success')");return "ok";
                        }));
                assertBatched(batcher,firstPulled,releaseDrain);
                assertFutureFailed(calls.getFirst());
                assertThat(calls.get(1).get(10,TimeUnit.SECONDS)).isEqualTo("ok");
                assertThat(trackingSource.commitAttempts()).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT point_key FROM batch_probe ORDER BY point_key",String.class)).containsExactly("B");
            } finally {releaseDrain.countDown();}
        } finally {pool.shutdownNow();}
    }

    @Test void anOuterCommitFailureFailsEverySubmittedCallAndPublishesNoSuccess() throws Exception {
        var failingSource=new CommitObservingDataSource(jdbc.getDataSource(),true);
        var batchJdbc=new JdbcTemplate(failingSource);var pool=Executors.newFixedThreadPool(2);
        var firstPulled=new CountDownLatch(1);var releaseDrain=new CountDownLatch(1);var gate=new AtomicBoolean(true);
        try(var batcher=new RawTelemetryCommitBatcher(batchJdbc,()->holdFirstDrain(gate,firstPulled,releaseDrain))) {
            try {
                var calls=concurrently(pool,
                        ()->batcher.execute(RawTelemetryCommitBatcher.Stage.RESERVE,"A",()->{
                            batchJdbc.update("INSERT INTO batch_probe VALUES ('A','must-rollback')");return "A";
                        }),
                        ()->batcher.execute(RawTelemetryCommitBatcher.Stage.RESERVE,"B",()->{
                            batchJdbc.update("INSERT INTO batch_probe VALUES ('B','must-rollback')");return "B";
                        }));
                assertBatched(batcher,firstPulled,releaseDrain);
                calls.forEach(RawTelemetryCommitBatcherTest::assertFutureFailed);
                assertThat(failingSource.commitAttempts()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_probe",Integer.class)).isZero();
            } finally {releaseDrain.countDown();}
        } finally {pool.shutdownNow();}
    }

    @Test void concurrentFirstReportsKeepOneFrozenSnapshotAndTDWriteFailureLeavesCommittedIntentPending() throws Exception {
        var first=event(100);var competing=event(120);var pool=Executors.newFixedThreadPool(2);
        var firstPulled=new CountDownLatch(1);var releaseDrain=new CountDownLatch(1);var gate=new AtomicBoolean(true);
        try(var batcher=new RawTelemetryCommitBatcher(jdbc,()->holdFirstDrain(gate,firstPulled,releaseDrain))) {
            try {
                var ledger=new RawTelemetryLedger(jdbc,codec,16,50000,batcher);
                var reservations=concurrently(pool,
                        ()->ledger.reserve(first,()->null),
                        ()->ledger.reserve(competing,()->null));
                assertBatched(batcher,firstPulled,releaseDrain);
                var a=reservations.get(0).get(10,TimeUnit.SECONDS);var b=reservations.get(1).get(10,TimeUnit.SECONDS);
                assertThat(List.of(a,b)).extracting(RawTelemetryLedger.Reservation::conflict).containsExactlyInAnyOrder(false,true);
                assertThat(a.original().value()).isEqualTo(b.original().value());

                var fact=ledger.find("P1",first.eventTime());
                var frozen=codec.decode(fact.snapshotJson());
                assertThat(fact.storageState()).isEqualTo("PENDING");
                assertThat(fact.snapshotJson()).isEqualTo(codec.encode(a.original()));
                assertThat(jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='PENDING_RAW'",Long.class)).isEqualTo(1);

                var writeStarted=new AtomicBoolean();
                assertThatThrownBy(()->ledger.storeFrozen(frozen,()->{
                    writeStarted.set(true);
                    assertThat(ledger.find("P1",frozen.eventTime()).storageState()).isEqualTo("PENDING");
                    throw new IllegalStateException("TD write failed");
                })).isInstanceOf(IllegalStateException.class).hasMessage("TD write failed");
                assertThat(writeStarted).isTrue();
                assertThat(ledger.find("P1",first.eventTime()).storageState()).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='PENDING_RAW'",Long.class)).isEqualTo(1);
            } finally {releaseDrain.countDown();}
        } finally {pool.shutdownNow();}
    }

    @Test void fullPendingQuotaRollsBackTheNewIntentAndItsBoundary() {
        jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=50000 WHERE budget_key='PENDING_RAW'");
        try(var batcher=new RawTelemetryCommitBatcher(jdbc)) {
            var ledger=new RawTelemetryLedger(jdbc,codec,16,50000,batcher);
            assertThatThrownBy(()->ledger.reserve(event(100),()->null)).isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessageContaining("capacity");
            assertThat(ledger.find("P1",event(100).eventTime())).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_retention_boundary",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='PENDING_RAW'",Long.class)).isEqualTo(50000);
        }
    }

    @Test void persistBatchCommitsSuccessfulTdWriteButKeepsFailedPeerPending() throws Exception {
        var trackingSource=new CommitObservingDataSource(jdbc.getDataSource(),false);
        var trackedJdbc=new JdbcTemplate(trackingSource);var pool=Executors.newFixedThreadPool(2);
        var firstPulled=new CountDownLatch(1);var releaseDrain=new CountDownLatch(1);var releaseSuccessfulWrite=new CountDownLatch(1);
        var blockNextDrain=new AtomicBoolean(false);var successStarted=new CountDownLatch(1);
        try(var batcher=new RawTelemetryCommitBatcher(trackedJdbc,()->holdFirstDrain(blockNextDrain,firstPulled,releaseDrain))) {
            try {
                var ledger=new RawTelemetryLedger(trackedJdbc,codec,16,50000,batcher);
                var first=event("P1",100);var second=event("P2",200);
                ledger.reserve(first,()->null);ledger.reserve(second,()->null);
                assertThat(jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='PENDING_RAW'",Long.class)).isEqualTo(2);
                int commitsBeforePersist=trackingSource.commitAttempts();
                blockNextDrain.set(true);

                var successful=pool.submit(()->ledger.storeFrozen(first,()->{
                    successStarted.countDown();await(releaseSuccessfulWrite);return "td-written";
                }));
                assertThat(firstPulled.await(5,TimeUnit.SECONDS)).isTrue();
                var failed=pool.submit(()->ledger.storeFrozen(second,()->{throw new IllegalStateException("TD write failed");}));
                awaitPending(batcher,1);
                releaseDrain.countDown();

                assertThat(successStarted.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(successful.isDone()).isFalse();
                assertThat(failed.isDone()).isFalse();
                assertThat(ledger.find("P1",first.eventTime()).storageState()).isEqualTo("PENDING");
                assertThat(ledger.find("P2",second.eventTime()).storageState()).isEqualTo("PENDING");

                releaseSuccessfulWrite.countDown();
                assertThat(successful.get(10,TimeUnit.SECONDS)).isEqualTo("td-written");
                assertFutureFailed(failed);
                assertThat(ledger.find("P1",first.eventTime()).storageState()).isEqualTo("PERSISTED");
                assertThat(ledger.find("P2",second.eventTime()).storageState()).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='PENDING_RAW'",Long.class)).isEqualTo(1);
                assertThat(trackingSource.commitAttempts()-commitsBeforePersist).isEqualTo(1);
            } finally {releaseDrain.countDown();releaseSuccessfulWrite.countDown();}
        } finally {pool.shutdownNow();}
    }

    private RawTelemetryEvent event(double value) {
        return new RawTelemetryEvent("P1","TEMP","MQTT_STANDARD_V1","MAC:A:TEMP","DEV1","B1",null,"E1","DEV1","TEMP",null,"TEMP",value,
                1800000000000L,1800000001000L,0,0,false);
    }

    private RawTelemetryEvent event(String pointId,double value) {
        return new RawTelemetryEvent(pointId,"TEMP","MQTT_STANDARD_V1","MAC:A:TEMP","DEV1","B1",null,"E1","DEV1","TEMP",null,"TEMP",value,
                1800000000000L,1800000001000L,0,0,false);
    }

    @SafeVarargs private static <T> List<Future<T>> concurrently(ExecutorService pool,Callable<T>... actions) throws InterruptedException {
        var ready=new CountDownLatch(actions.length);var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
        for(var action:actions)futures.add(pool.submit(()->{ready.countDown();await(start);return action.call();}));
        if(!ready.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Concurrent test calls did not start");
        start.countDown();return futures;
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Timed out waiting for test coordination");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
    }

    private static void assertFutureFailed(Future<?> future) {
        assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    private static void holdFirstDrain(AtomicBoolean gate,CountDownLatch pulled,CountDownLatch release) {
        if(gate.compareAndSet(true,false)){pulled.countDown();await(release);}
    }

    private static void assertBatched(RawTelemetryCommitBatcher batcher,CountDownLatch pulled,CountDownLatch release) throws InterruptedException {
        assertThat(pulled.await(5,TimeUnit.SECONDS)).isTrue();
        awaitPending(batcher,1);
        release.countDown();
    }

    private static void awaitPending(RawTelemetryCommitBatcher batcher,int count) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(batcher.pendingCount()<count&&System.nanoTime()<deadline)Thread.onSpinWait();
        assertThat(batcher.pendingCount()).isGreaterThanOrEqualTo(count);
    }

    private static final class CommitObservingDataSource extends DelegatingDataSource {
        private final AtomicInteger commitAttempts=new AtomicInteger();
        private final boolean failCommit;
        CommitObservingDataSource(DataSource target,boolean failCommit){super(target);this.failCommit=failCommit;}
        int commitAttempts(){return commitAttempts.get();}
        @Override public Connection getConnection() throws SQLException {
            var delegate=super.getConnection();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if("commit".equals(method.getName())){
                    commitAttempts.incrementAndGet();if(failCommit)throw new SQLException("forced commit failure");
                }
                try{return method.invoke(delegate,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
    }
}
