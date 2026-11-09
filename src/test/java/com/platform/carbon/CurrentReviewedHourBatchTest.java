package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.identity.DeviceIdentityProvider;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.platform.carbon.DynamicElectricityRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local H2 contract tests, not evidence of MySQL query plans or target YEAR latency. */
class CurrentReviewedHourBatchTest extends QueryDayCacheFixture {
    private DynamicElectricityLiveRepository tasks;
    private DynamicElectricityLiveService background;
    private DynamicElectricityService calculations;
    private SysUserMapper users;
    private SysRoleMapper roles;

    @BeforeEach void backgroundSetup() throws Exception {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("src/env/init/V105__mysql_dynamic_electricity_tasks.sql"));
        }
        tasks = spy(new DynamicElectricityLiveRepository(jdbc, new ObjectMapper().findAndRegisterModules()));
        calculations = spy(service);
        users = mock(SysUserMapper.class);
        roles = mock(SysRoleMapper.class);
        var user = new SysUser();
        user.setStatus(1); user.setDelFlag(0); user.setActivationPending(0);
        when(users.selectSecurityState(USER)).thenReturn(user);
        when(roles.selectRoleKeysByUserId(USER)).thenReturn(new ArrayList<>(ROLES));
        background = spy(new DynamicElectricityLiveService(authorization, mock(DeviceIdentityProvider.class),
                tasks, repository, mock(DynamicElectricityLiveInput.class), calculations, users, roles, transactionManager,mock(DynamicElectricitySourceChecks.class)));
    }

    private List<DynamicElectricityLiveRepository.Task> prepare(int hours) {
        for (var result : saveHours(1, hours)) tasks.registerCalculation(result, USER);
        jdbc.update("UPDATE biz_dynamic_electricity_task SET next_check=0");
        return tasks.due(System.currentTimeMillis());
    }

    private String complete(DynamicElectricityLiveRepository.Task task, String source) {
        return calculations.sourceVersionWithReviewedSnapshot(task.building(), Instant.ofEpochMilli(task.from()),
                Instant.ofEpochMilli(task.to()), source);
    }

    private List<String> states() {
        return jdbc.queryForList("SELECT state FROM biz_dynamic_electricity_task ORDER BY start_ms", String.class);
    }

    @Test void sharedLongFactIsReadOnceAndAllTwentyFourFullSourceHashesMatchTheOriginal() {
        batch("approved", "APPROVED", 1);
        fact("long", "approved", "P", FROM, DAY_END, "{\"quantity\":24}", null);
        var group = prepare(24);
        var expected = group.stream().map(task -> calculations.sourceVersion(task.building(),
                Instant.ofEpochMilli(task.from()), Instant.ofEpochMilli(task.to()), REVIEWED)).toList();
        clearInvocations(repository, live);

        var actual = CurrentReviewedHourBatch.read(repository, transactionManager, group, this::complete);

        assertThat(actual.map(CurrentReviewedHourBatch.Versions::values)).contains(expected);
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository).currentReviewedDayBodies(eq(BUILDING), eq(FROM), eq(DAY_END), eq(List.of("long")), any());
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
        verify(repository, times(24)).factors(eq(BUILDING), any(), any());
        verify(live, times(24)).metadataVersion(BUILDING, repository);
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test void independent219PointHoursKeepExactHashesAndReadEveryRawFactOnce() {
        seedDay(219, 24);
        var group = prepare(24);
        var expected = group.stream().map(DynamicElectricityLiveRepository.Task::metadata).toList();
        clearInvocations(repository);

        assertThat(CurrentReviewedHourBatch.read(repository, transactionManager, group, this::complete)
                .map(CurrentReviewedHourBatch.Versions::values)).contains(expected);

        var requested = new ArrayList<String>();
        mockingDetails(repository).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("currentReviewedDayBodies"))
                .forEach(call -> {
                    List<String> ids = call.getArgument(3);
                    assertThat(ids).hasSizeBetween(1, 256);
                    requested.addAll(ids);
                });
        assertThat(requested).hasSize(219 * 24).doesNotHaveDuplicates();
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"2,false", "12,false", "24,false", "2,true", "12,true", "24,true"})
    @SuppressWarnings("unchecked")
    void exactGroupWindowNeverReadsTheOtherHoursRawBodies(int hours, boolean crossing) {
        seedDay(219, 24);
        if (crossing) fact("cross-boundaries", "approved", "CROSS", FROM.minusSeconds(3600),
                DAY_END.plusSeconds(3600), "{\"cross\":\"" + "x".repeat(1000) + "\"}", null);
        fact("outside-group", "approved", "OUTSIDE", FROM.plusSeconds(hours * 3600L).plusMillis(1),
                DAY_END.plusSeconds(7200), "{\"outside\":\"" + "z".repeat(2000) + "\"}", null);
        var all = prepare(24);
        var group = all.subList(0, hours);
        long oldRows = 0, oldBytes = 0;
        for (var task : group) {
            // This fixture has no superseding or unapproved rows; this is the original query's
            // returned payload set, measured separately from the new metadata/body reader.
            var payloads = jdbc.queryForList("""
                    SELECT reading_json FROM biz_energy_manual_reading
                    WHERE building_id=? AND start_inclusive<? AND end_exclusive>?
                    """, String.class, BUILDING, task.to(), task.from());
            oldRows += payloads.size();
            for (String payload : payloads) oldBytes += payload.getBytes(StandardCharsets.UTF_8).length;
        }
        var rowsRead = new AtomicLong(); var bytesRead = new AtomicLong(); var bodyCalls = new AtomicInteger();
        doAnswer(call -> {
            var facts = (List<CurrentReviewedDayVersionRead.Fact>) call.callRealMethod();
            bodyCalls.incrementAndGet(); rowsRead.addAndGet(facts.size());
            for (var fact : facts) bytesRead.addAndGet(fact.bytes());
            return facts;
        }).when(repository).currentReviewedDayBodies(anyString(), any(), any(), anyList(), any());
        clearInvocations(repository);

        var actual = CurrentReviewedHourBatch.read(repository, transactionManager, group, this::complete);

        assertThat(actual.map(CurrentReviewedHourBatch.Versions::values))
                .contains(group.stream().map(DynamicElectricityLiveRepository.Task::metadata).toList());
        assertThat(rowsRead.get()).isEqualTo(219L * hours + (crossing ? 1 : 0)).isLessThanOrEqualTo(oldRows);
        assertThat(bytesRead.get()).isLessThanOrEqualTo(oldBytes);
        if (!crossing) assertThat(bytesRead.get()).isEqualTo(oldBytes);
        long metadataCalls = mockingDetails(repository).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("currentReviewedDayCandidates")).count();
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(FROM.plusSeconds(hours * 3600L)), any());
        System.out.printf("BACKGROUND_SOURCE_IO hours=%d crossing=%s oldRows=%d newRows=%d oldBytes=%d newBytes=%d oldQueries=%d newMetadataQueries=%d newBodyQueries=%d%n",
                hours, crossing, oldRows, rowsRead.get(), oldBytes, bytesRead.get(), hours, metadataCalls, bodyCalls.get());
    }

    @Test void unchangedGroupReauthorizesEveryTaskOutsideSnapshotAndClaimsOnlyAfterItCloses() {
        seedDay(1, 2); prepare(2);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(tasks).claimReviewedCheck(any(), anyLong());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(authorization).requireCalculationRunner(eq(USER), anyCollection(), eq(BUILDING));

        background.refresh();

        assertThat(states()).containsExactly("READY", "READY");
        verify(users, times(3)).selectSecurityState(USER);
        verify(roles, times(3)).selectRoleKeysByUserId(USER);
        verify(tasks, times(2)).checkedReviewedBatch(any(), anyString(), anyLong());
        verify(calculations, never()).calculate(anyLong(), anyCollection(), anyString(), any());
    }

    @Test void revokedAccountAfterSnapshotCannotBeReportedChecked() {
        seedDay(1, 2); prepare(2);
        var disabled = new SysUser(); disabled.setStatus(0); disabled.setDelFlag(0); disabled.setActivationPending(0);
        doAnswer(call -> {
            Object version = call.callRealMethod();
            when(users.selectSecurityState(USER)).thenReturn(disabled);
            return version;
        }).when(calculations).sourceVersionWithReviewedSnapshot(anyString(), any(), any(), anyString());

        background.refresh();

        assertThat(states()).containsExactly("WAITING", "WAITING");
        verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
        verify(calculations, never()).calculate(anyLong(), anyCollection(), anyString(), any());
    }

    @Test void changedRolesTakeTheOriginalFreshSourcePath() {
        seedDay(1, 2); prepare(2);
        doAnswer(call -> {
            Object version = call.callRealMethod();
            when(roles.selectRoleKeysByUserId(USER)).thenReturn(List.of("PLATFORM_ADMIN"));
            return version;
        }).when(calculations).sourceVersionWithReviewedSnapshot(anyString(), any(), any(), anyString());
        clearInvocations(repository);

        background.refresh();

        assertThat(states()).containsExactly("READY", "READY");
        verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
        verify(repository, times(2)).electricityVersion(eq(BUILDING), any(), any(), eq(REVIEWED));
    }

    @Test void capacityFallbackUsesOriginalPerTaskPathWithoutKeepingSnapshot() {
        seedDay(1, 2); prepare(2);
        doReturn(Optional.empty()).when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
        clearInvocations(repository);

        background.refresh();

        assertThat(states()).containsExactly("READY", "READY");
        verify(repository, times(2)).electricityVersion(eq(BUILDING), any(), any(), eq(REVIEWED));
        verify(tasks, never()).claimReviewedCheck(any(), anyLong());
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test void sqlFailureEntersExistingRetryStateWithoutOriginalReadFallback() {
        seedDay(1, 2); prepare(2);
        doThrow(new QueryTimeoutException("forced body timeout")).when(repository)
                .currentReviewedDayBodies(anyString(), any(), any(), anyList(), any());
        clearInvocations(repository);

        background.refresh();

        assertThat(states()).containsExactly("WAITING", "WAITING");
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
        verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test void realTransactionExpiryPropagatesAndReleasesTheSnapshot() {
        seedDay(1, 2); var group = prepare(2);
        assertThatThrownBy(() -> CurrentReviewedHourBatch.read(repository, transactionManager, group, (task, source) -> {
            var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            assertThat(holder).isNotNull();
            assertThat(holder.hasTimeout()).isTrue();
            holder.setTimeoutInMillis(0);
            return source;
        })).isInstanceOf(org.springframework.transaction.TransactionTimedOutException.class);
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test void taskRegistrationChangingActorWithoutRevisionCannotClaimStalePrecheck() {
        seedDay(1, 2); var group = prepare(2);
        var first = group.getFirst();
        jdbc.update("UPDATE biz_dynamic_electricity_task SET requested_by=? WHERE start_ms=?", USER + 1, first.from());
        assertThat(tasks.claimReviewedCheck(first, System.currentTimeMillis())).isNull();
        assertThat(states()).containsExactly("READY", "READY");
    }

    @Test void dirtyRevisionOrReplacementTokenCannotBeOverwrittenByChecked() {
        seedDay(1, 2); var group = prepare(2);
        var first = group.getFirst(); long now = System.currentTimeMillis();
        String token = tasks.claimReviewedCheck(first, now);
        assertThat(token).isNotNull();
        tasks.invalidateLegacy(BUILDING, Instant.ofEpochMilli(first.from()), Instant.ofEpochMilli(first.to()));
        assertThat(tasks.checkedReviewedBatch(first, token, now)).isFalse();
        assertThat(states()).containsExactly("PENDING", "READY");
        var second = group.get(1);
        String secondToken = tasks.claimReviewedCheck(second, now);
        jdbc.update("UPDATE biz_dynamic_electricity_task SET lease_token='replacement' WHERE start_ms=?", second.from());
        assertThat(tasks.checkedReviewedBatch(second, secondToken, now)).isFalse();
    }

    @Test void expiredLeaseIsNotCheckedAndRemainsRecoverable() {
        seedDay(1, 2); var task = prepare(2).getFirst();
        long now = System.currentTimeMillis(); String token = tasks.claimReviewedCheck(task, now);
        assertThat(tasks.checkedReviewedBatch(task, token, now + 30001)).isFalse();
        assertThat(tasks.due(now + 30001)).extracting(DynamicElectricityLiveRepository.Task::from).contains(task.from());
    }

    @Test void leaseExpirationObservedAfterAcquiringTheRowLockCannotMarkReady() {
        seedDay(1, 2); prepare(2);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            Long lease = (Long) call.callRealMethod();
            assertThat(lease).isNotNull();
            // Deterministically model a lock acquired only after the lease's expiry, without
            // pretending a caller's pre-SQL timestamp proves that a blocking UPDATE was safe.
            return System.currentTimeMillis() - 1;
        }).when(tasks).lockReviewedCheck(any(), anyString());

        background.refresh();

        verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
        assertThat(states()).containsExactly("WAITING", "READY");
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test void failedCheckedUpdateRollsBackAndUsesTheExistingRetryState() {
        seedDay(1, 2); prepare(2);
        doAnswer(call -> {
            call.callRealMethod();
            throw new QueryTimeoutException("forced post-update failure");
        }).when(tasks).checkedReviewedBatch(any(), anyString(), anyLong());

        background.refresh();

        // Rollback restores the token, so failed() can move the first task to WAITING.
        assertThat(states()).containsExactly("WAITING", "READY");
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test void twoConnectionLeaseExpiryIsRejectedAfterCurrentRowRead() throws Exception {
        seedDay(1, 2); prepare(2);
        var enteredLock = new CountDownLatch(1);
        var blocker = new AtomicReference<java.sql.Connection>();
        var release = new AtomicReference<Future<?>>();
        var executor = Executors.newSingleThreadExecutor();
        doAnswer(call -> {
            String token = (String) call.callRealMethod();
            DynamicElectricityLiveRepository.Task task = call.getArgument(0);
            if (task.from() == FROM.toEpochMilli()) {
                var connection = dataSource.getConnection(); connection.setAutoCommit(false); blocker.set(connection);
                try (var statement = connection.prepareStatement("UPDATE biz_dynamic_electricity_task SET lease_until=? WHERE start_ms=?")) {
                    statement.setLong(1, System.currentTimeMillis() + 50);
                    statement.setLong(2, task.from()); statement.executeUpdate();
                }
                release.set(executor.submit(() -> {
                    try {
                        assertThat(enteredLock.await(5, TimeUnit.SECONDS)).isTrue();
                        Thread.sleep(100);
                        connection.commit();
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                }));
            }
            return token;
        }).when(tasks).claimReviewedCheck(any(), anyLong());
        doAnswer(call -> {
            DynamicElectricityLiveRepository.Task task = call.getArgument(0);
            if (task.from() == FROM.toEpochMilli()) enteredLock.countDown();
            return call.callRealMethod();
        }).when(tasks).lockReviewedCheck(any(), anyString());
        try {
            background.refresh();
            release.get().get(5, TimeUnit.SECONDS);
            assertThat(states()).containsExactly("WAITING", "READY");
            verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
        } finally {
            executor.shutdownNow();
            if (blocker.get() != null) { blocker.get().rollback(); blocker.get().close(); }
        }
    }

    @Test void productionPostUpdateLeaseCheckRollsBackADelayedUpdate() {
        seedDay(1, 2); prepare(2);
        var clock = new AtomicLong(System.currentTimeMillis());
        var lease = new AtomicLong();
        doAnswer(call -> clock.get()).when(background).reviewedCheckTimeMillis();
        doAnswer(call -> {
            Long expiry = (Long) call.callRealMethod();
            assertThat(expiry).isNotNull();
            lease.set(expiry);
            return expiry;
        }).when(tasks).lockReviewedCheck(any(), anyString());
        doAnswer(call -> {
            Object checked = call.callRealMethod();
            // Advance only after the real UPDATE. This deterministically exercises the production
            // post-statement branch instead of depending on a 100 ms scheduling window in CI.
            clock.set(lease.get() + 1);
            return checked;
        }).when(tasks).checkedReviewedBatch(any(), anyString(), anyLong());

        background.refresh();

        assertThat(states()).containsExactly("WAITING", "READY");
        verify(tasks).checkedReviewedBatch(any(), anyString(), anyLong());
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"2", "3", "4"})
    void ownedClaimSnapshotExpiryEntersWaitingBeforeTransactionAfterLockOrAfterUpdate(int expireOnClockRead) {
        seedDay(1, 2); prepare(2);
        var clockReads = new AtomicInteger();
        doAnswer(call -> System.nanoTime() + (clockReads.incrementAndGet() >= expireOnClockRead
                ? TimeUnit.SECONDS.toNanos(21) : 0)).when(background).reviewedCheckNanoTime();

        background.refresh();

        assertThat(states()).containsExactly("WAITING", "READY");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM biz_dynamic_electricity_task WHERE start_ms=?",
                String.class, FROM.toEpochMilli())).isNull();
        assertThat(jdbc.queryForObject("SELECT last_error FROM biz_dynamic_electricity_task WHERE start_ms=?",
                String.class, FROM.toEpochMilli())).isEqualTo("DYNAMIC_COMPUTE_FAILED");
        verify(tasks).failed(argThat(task -> task.from() == FROM.toEpochMilli()), anyString(), eq("DYNAMIC_COMPUTE_FAILED"));
        if (expireOnClockRead < 4) verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
        else verify(tasks).checkedReviewedBatch(any(), anyString(), anyLong());
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test void lostOwnershipAfterClaimDoesNotChangeTheReplacementTask() {
        seedDay(1, 2); prepare(2);
        var authorizations = new AtomicInteger();
        doAnswer(call -> {
            if (authorizations.incrementAndGet() == 2)
                jdbc.update("UPDATE biz_dynamic_electricity_task SET lease_token='replacement' WHERE start_ms=?",
                        FROM.toEpochMilli());
            return null;
        }).when(authorization).requireCalculationRunner(eq(USER), anyCollection(), eq(BUILDING));

        background.refresh();

        assertThat(states()).containsExactly("RUNNING", "READY");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM biz_dynamic_electricity_task WHERE start_ms=?",
                String.class, FROM.toEpochMilli())).isEqualTo("replacement");
        verify(tasks, never()).failed(any(), anyString(), anyString());
        verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
    }

    @Test void snapshotTimeIsExplicitAndAnOutOfBandSourceChangeIsSeenOnTheNextPoll() {
        seedDay(1, 2); prepare(2);
        var changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true))
                jdbc.update("UPDATE biz_energy_manual_reading SET reading_json='changed-after-snapshot'");
            return call.callRealMethod();
        }).when(tasks).claimReviewedCheck(any(), anyLong());
        doReturn(List.of()).when(calculations).calculate(anyLong(), anyCollection(), anyString(), any());

        background.refresh();
        assertThat(states()).containsExactly("READY", "READY");
        verify(calculations, never()).calculate(anyLong(), anyCollection(), anyString(), any());
        jdbc.update("UPDATE biz_dynamic_electricity_task SET next_check=0");
        background.refresh();

        // The existing calculation path is invoked, not a cached publication. Its separate tests
        // own the final source-version recheck and atomic result/audit transaction.
        verify(calculations, times(2)).calculate(eq(USER), anyCollection(), eq(BUILDING), any());
    }

    @Test void factorCorruptionIsNotConvertedToCurrent() {
        seedDay(1, 2); prepare(2);
        jdbc.update("UPDATE biz_dynamic_electricity_factor SET payload_hash=?", "0".repeat(64));
        background.refresh();
        assertThat(states()).containsExactly("WAITING", "WAITING");
        verify(tasks, never()).checkedReviewedBatch(any(), anyString(), anyLong());
    }

    @Test void singletonCrossDayCrossUserAndOverBoundGroupsNeverReadASnapshot() {
        seedDay(1, 3); var group = prepare(3);
        var first = group.getFirst();
        var foreign = new DynamicElectricityLiveRepository.Task(first.building(), first.from(), first.to(), USER + 1,
                first.inputHash(), first.revision(), first.state(), first.token(), first.leaseUntil(), first.metadata(),
                first.resultId(), first.sourceKind(), first.grain(), first.readings());
        var tomorrow = new DynamicElectricityLiveRepository.Task(first.building(), first.from() + 86_400_000,
                first.to() + 86_400_000, first.user(), first.inputHash(), first.revision(), first.state(),
                first.token(), first.leaseUntil(), first.metadata(), first.resultId(), first.sourceKind(), first.grain(), first.readings());
        clearInvocations(repository);
        for (var invalid : List.of(List.of(first), List.of(first, foreign), List.of(first, tomorrow),
                List.of(first, group.get(2)), Collections.nCopies(25, first)))
            assertThat(CurrentReviewedHourBatch.read(repository, transactionManager, invalid, this::complete)).isEmpty();
        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
    }

    @Test void dayMonthRawAndNonReadyTasksNeverQualifyForBatchReuse() {
        seedDay(1, 2); var first = prepare(2).getFirst();
        for (String grain : List.of("DAY", "MONTH")) {
            var coarse = new DynamicElectricityLiveRepository.Task(first.building(), first.from(), first.to(), first.user(),
                    first.inputHash(), first.revision(), first.state(), first.token(), first.leaseUntil(), first.metadata(),
                    first.resultId(), first.sourceKind(), grain, first.readings());
            assertThat(CurrentReviewedHourBatch.eligible(coarse)).isFalse();
        }
        var raw = new DynamicElectricityLiveRepository.Task(first.building(), first.from(), first.to(), first.user(),
                first.inputHash(), first.revision(), first.state(), first.token(), first.leaseUntil(), first.metadata(),
                first.resultId(), "RAW_CUMULATIVE", first.grain(), first.readings());
        var waiting = new DynamicElectricityLiveRepository.Task(first.building(), first.from(), first.to(), first.user(),
                first.inputHash(), first.revision(), "WAITING", first.token(), first.leaseUntil(), first.metadata(),
                first.resultId(), first.sourceKind(), first.grain(), first.readings());
        assertThat(CurrentReviewedHourBatch.eligible(raw)).isFalse();
        assertThat(CurrentReviewedHourBatch.eligible(waiting)).isFalse();
    }

    @Test void versionsDoNotSurviveIntoAnotherPollAndExpiredBatchCannotQualify() {
        seedDay(1, 2); prepare(2);
        clearInvocations(repository);
        background.refresh();
        jdbc.update("UPDATE biz_dynamic_electricity_task SET next_check=0");
        background.refresh();
        verify(repository, times(2)).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(FROM.plusSeconds(7200)), any());
        assertThat(new CurrentReviewedHourBatch.Versions(List.of("version"), System.nanoTime() - 1).usable()).isFalse();
    }

    @Test void interleavedPrincipalsKeepDueOrderAndCorrectnessWithoutClaimingBatchSavings() {
        seedDay(1, 4); var group = prepare(4);
        var activeUser = users.selectSecurityState(USER);
        when(users.selectSecurityState(USER + 1)).thenReturn(activeUser);
        when(roles.selectRoleKeysByUserId(USER + 1)).thenReturn(new ArrayList<>(ROLES));
        jdbc.update("UPDATE biz_dynamic_electricity_task SET requested_by=? WHERE start_ms IN (?,?)",
                USER + 1, group.get(1).from(), group.get(3).from());
        clearInvocations(repository);

        background.refresh();

        assertThat(states()).containsExactly("READY", "READY", "READY", "READY");
        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
        verify(repository, times(4)).electricityVersion(eq(BUILDING), any(), any(), eq(REVIEWED));
    }
}
