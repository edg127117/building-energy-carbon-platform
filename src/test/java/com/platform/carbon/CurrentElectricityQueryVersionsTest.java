package com.platform.carbon;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Eligibility and lifetime are tied to a real caller-owned transaction, not annotations alone. */
class CurrentElectricityQueryVersionsTest extends QueryDayCacheFixture {
    @Test void directCallWithoutTransactionKeepsTheOriginalPerPeriodReads() {
        seedDay(2, 2);
        var expected = originalVersion(FROM, FROM.plusSeconds(3600));
        clearInvocations(repository);

        try (var cache = cache(twoHours(FROM))) {
            assertThat(version(cache, FROM)).isEqualTo(expected);
            assertThat(cache.hasRetainedDay()).isFalse();
        }

        verify(repository).electricityVersion(BUILDING, FROM, FROM.plusSeconds(3600), REVIEWED);
        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
    }

    @Test void readCommittedWritableAndDefaultTransactionsCannotEnableTheCache() {
        seedDay(1, 2);
        var expected = originalVersion(FROM, FROM.plusSeconds(3600));
        var transactions = List.of(
                transaction(true, TransactionDefinition.ISOLATION_READ_COMMITTED),
                transaction(false, TransactionDefinition.ISOLATION_REPEATABLE_READ),
                transaction(true, TransactionDefinition.ISOLATION_DEFAULT),
                transaction(false, TransactionDefinition.ISOLATION_DEFAULT));
        clearInvocations(repository);

        for (var transaction : transactions) {
            transaction.executeWithoutResult(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                try (var cache = cache(twoHours(FROM))) {
                    assertThat(version(cache, FROM)).isEqualTo(expected);
                    assertThat(cache.hasRetainedDay()).isFalse();
                }
            });
        }

        verify(repository, times(4)).electricityVersion(BUILDING, FROM, FROM.plusSeconds(3600), REVIEWED);
        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
    }

    @Test void explicitReadOnlyRepeatableReadUsesOneDayForTwoDistinctHours() {
        seedDay(3, 2);
        var first = originalVersion(FROM, FROM.plusSeconds(3600));
        var second = originalVersion(FROM.plusSeconds(3600), FROM.plusSeconds(7200));
        clearInvocations(repository);

        readOnlyRepeatableRead().executeWithoutResult(status -> {
            var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            assertThat(holder).isNotNull();
            assertThat(holder.isSynchronizedWithTransaction()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            try {
                assertThat(holder.getConnection().getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            } catch (java.sql.SQLException error) {
                throw new AssertionError(error);
            }
            try (var cache = cache(twoHours(FROM))) {
                assertThat(version(cache, FROM)).isEqualTo(first);
                assertThat(version(cache, FROM.plusSeconds(3600))).isEqualTo(second);
                assertThat(cache.hasRetainedDay()).isTrue();
            }
        });

        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
    }

    @Test void anUnsynchronizedConnectionHolderCannotQualifyAsTheSnapshot() {
        seedDay(1, 2);
        clearInvocations(repository);

        readOnlyRepeatableRead().executeWithoutResult(status -> {
            var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            holder.setSynchronizedWithTransaction(false);
            try (var cache = cache(twoHours(FROM))) {
                assertThat(version(cache, FROM)).isEqualTo(originalVersion(FROM, FROM.plusSeconds(3600)));
                assertThat(cache.hasRetainedDay()).isFalse();
            } finally {
                holder.setSynchronizedWithTransaction(true);
            }
        });

        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
        verify(repository).electricityVersion(BUILDING, FROM, FROM.plusSeconds(3600), REVIEWED);
    }

    @Test void newTransactionResourceDisablesTheOldCacheEvenAfterTheOriginalResumes() {
        seedDay(1, 2);
        clearInvocations(repository);

        readOnlyRepeatableRead().executeWithoutResult(status -> {
            Object originalResource = TransactionSynchronizationManager.getResource(dataSource);
            try (var cache = cache(twoHours(FROM))) {
                var expected = version(cache, FROM);
                assertThat(cache.hasRetainedDay()).isTrue();
                var requiresNew = readOnlyRepeatableRead();
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.executeWithoutResult(inner -> {
                    assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNotSameAs(originalResource);
                    assertThat(version(cache, FROM)).isEqualTo(expected);
                    assertThat(cache.hasRetainedDay()).isFalse();
                });
                assertThat(TransactionSynchronizationManager.getResource(dataSource)).isSameAs(originalResource);
                assertThat(version(cache, FROM)).isEqualTo(expected);
                assertThat(cache.hasRetainedDay()).isFalse();
            }
        });

        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, times(2)).electricityVersion(BUILDING, FROM, FROM.plusSeconds(3600), REVIEWED);
    }

    @Test void contextChangeIsRejectedBeforeAnySourceReadAndRoleOrderingDoesNotChangeIdentity() {
        seedDay(1, 2);
        readOnlyRepeatableRead().executeWithoutResult(status -> {
            try (var cache = cache(twoHours(FROM))) {
                clearInvocations(repository);
                assertThatThrownBy(() -> cache.version(USER + 1, ROLES, BUILDING, FROM, FROM.plusSeconds(3600), REVIEWED))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("context changed");
                assertThatThrownBy(() -> cache.version(USER, Set.of("ADMIN"), BUILDING, FROM, FROM.plusSeconds(3600), REVIEWED))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("context changed");
                assertThatThrownBy(() -> cache.version(USER, ROLES, "OTHER", FROM, FROM.plusSeconds(3600), REVIEWED))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("context changed");
                verifyNoInteractions(repository);
                assertThat(cache.version(USER, List.of("CARBON_READER", "ENERGY_MANAGER"), BUILDING,
                        FROM, FROM.plusSeconds(3600), REVIEWED)).isEqualTo(originalVersion(FROM, FROM.plusSeconds(3600)));
            }
        });
    }

    @Test void rawNonHourUnalignedAndOneDistinctHourCandidatesStayOnTheOriginalPath() {
        seedDay(1, 2);
        var single = chart(FROM, "HOUR", REVIEWED);
        var candidateSets = List.of(
                List.of(single),
                List.of(single, single),
                List.of(chart(FROM, "DAY", REVIEWED), chart(FROM.plusSeconds(3600), "DAY", REVIEWED)),
                List.of(chart(FROM, "HOUR", "RAW_CUMULATIVE"), chart(FROM.plusSeconds(3600), "HOUR", "RAW_CUMULATIVE")),
                List.of(chart(FROM.plusSeconds(1), "HOUR", REVIEWED), chart(FROM.plusSeconds(3601), "HOUR", REVIEWED)));
        clearInvocations(repository);

        readOnlyRepeatableRead().executeWithoutResult(status -> {
            for (var candidates : candidateSets) {
                try (var cache = cache(candidates)) {
                    assertThat(version(cache, FROM)).isEqualTo(originalVersion(FROM, FROM.plusSeconds(3600)));
                    assertThat(cache.hasRetainedDay()).isFalse();
                }
            }
            try (var cache = cache(twoHours(FROM))) {
                String raw = repository.electricityVersion(BUILDING, FROM, FROM.plusSeconds(3600), "RAW_CUMULATIVE");
                assertThat(cache.version(USER, ROLES, BUILDING, FROM, FROM.plusSeconds(3600), "RAW_CUMULATIVE")).isEqualTo(raw);
                assertThat(cache.version(USER, ROLES, BUILDING, FROM, FROM.plusSeconds(7200), REVIEWED))
                        .isEqualTo(originalVersion(FROM, FROM.plusSeconds(7200)));
                assertThat(cache.hasRetainedDay()).isFalse();
            }
        });

        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
    }

    @Test void movingBetweenDaysReleasesRatherThanRetainingMultipleSnapshotsAndCloseClearsIt() {
        seedDay(1, 2);
        fact("next-day", "approved", "P", DAY_END, DAY_END.plusSeconds(7200), "{}", null);
        var candidates = new ArrayList<>(twoHours(FROM));
        candidates.addAll(twoHours(DAY_END));
        var retained = new AtomicReference<CurrentElectricityQueryVersions>();
        clearInvocations(repository);

        readOnlyRepeatableRead().executeWithoutResult(status -> {
            try (var cache = cache(candidates)) {
                retained.set(cache);
                for (Instant from : List.of(FROM, DAY_END, FROM)) {
                    assertThat(version(cache, from)).isEqualTo(originalVersion(from, from.plusSeconds(3600)));
                    assertThat(cache.hasRetainedDay()).isTrue();
                }
                assertThat(version(cache, DAY_END.plusSeconds(86400)))
                        .isEqualTo(originalVersion(DAY_END.plusSeconds(86400), DAY_END.plusSeconds(90000)));
                assertThat(cache.hasRetainedDay()).isFalse();
                version(cache, FROM);
                assertThat(cache.hasRetainedDay()).isTrue();
            }
        });

        assertThat(retained.get().hasRetainedDay()).isFalse();
        assertThatThrownBy(() -> version(retained.get(), FROM)).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        verify(repository, times(3)).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(DAY_END), eq(DAY_END.plusSeconds(86400)), any());
    }

    @Test void unavailableDayFallsBackForEveryHourWithoutRepeatedBuildsOrAnEmptyHash() {
        seedDay(2, 2);
        doReturn(Optional.empty()).when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
        clearInvocations(repository);

        readOnlyRepeatableRead().executeWithoutResult(status -> {
            try (var cache = cache(twoHours(FROM))) {
                for (Instant from : List.of(FROM, FROM.plusSeconds(3600))) {
                    assertThat(version(cache, from)).isEqualTo(originalVersion(from, from.plusSeconds(3600)));
                    assertThat(cache.hasRetainedDay()).isFalse();
                }
            }
        });

        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, times(2)).electricityVersion(eq(BUILDING), any(), any(), eq(REVIEWED));
    }

    @Test void exhaustedBudgetFailsWithoutStartingACacheOrFallingBackToTheOriginalQuery() {
        seedDay(1, 2);
        clearInvocations(repository);

        readOnlyRepeatableRead().executeWithoutResult(status -> {
            try (var cache = new CurrentElectricityQueryVersions(repository, USER, ROLES, BUILDING,
                    twoHours(FROM), () -> Duration.ZERO)) {
                assertThatThrownBy(()->version(cache, FROM)).isInstanceOf(QueryTimeoutException.class);
                assertThatThrownBy(()->version(cache, FROM.plusSeconds(3600))).isInstanceOf(QueryTimeoutException.class);
                assertThat(cache.hasRetainedDay()).isFalse();
            }
        });

        verify(repository, never()).loadCurrentReviewedDay(anyString(), any(), any(), any());
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
    }

    @Test void deadlineFailureAfterAnUnavailableDayStillCannotReachTheFallbackQuery() {
        seedDay(1,2);
        var left=new AtomicReference<>(Duration.ofSeconds(30));
        doAnswer(call->{left.set(Duration.ZERO);return Optional.empty();})
                .when(repository).loadCurrentReviewedDay(anyString(),any(),any(),any());
        clearInvocations(repository);
        readOnlyRepeatableRead().executeWithoutResult(status->{
            try(var cache=new CurrentElectricityQueryVersions(repository,USER,ROLES,BUILDING,twoHours(FROM),left::get)) {
                assertThatThrownBy(()->version(cache,FROM)).isInstanceOf(QueryTimeoutException.class);
            }
        });
        verify(repository).loadCurrentReviewedDay(eq(BUILDING),eq(FROM),eq(DAY_END),any());
        verify(repository,never()).electricityVersion(anyString(),any(),any(),anyString());
    }

    @Test void legalLongGetBuildsLaterDaysAfter45And121SecondsWithoutAnAgeDeadline() {
        seedDay(1, 2);
        Instant nextMonth=Instant.parse("2025-02-28T16:00:00Z");
        fact("next-day", "approved", "P", DAY_END, DAY_END.plusSeconds(7200), "{}", null);
        fact("next-month", "approved", "P", nextMonth, nextMonth.plusSeconds(7200), "{}", null);
        var days=List.of(FROM,DAY_END,nextMonth);
        var expected=days.stream().map(from->originalVersion(from,from.plusSeconds(3600))).toList();
        var candidates=new ArrayList<DynamicElectricityRepository.ChartResult>();
        days.forEach(day->candidates.addAll(twoHours(day)));
        var elapsed=new AtomicLong();
        var observed=new ArrayList<Duration>();
        doAnswer(call->{
            java.util.function.Supplier<Duration> budget=call.getArgument(3);
            observed.add(budget.get());
            return call.callRealMethod();
        }).when(repository).loadCurrentReviewedDay(anyString(),any(),any(),any());
        clearInvocations(repository);

        // A controlled caller clock advances between day loads. The production constructor is used,
        // and starting an unrelated GET-age timer is forbidden by the assertion below.
        try(var deadline=mockStatic(CarbonCalculationDeadline.class,CALLS_REAL_METHODS)) {
            deadline.when(CarbonCalculationDeadline::currentRemaining)
                    .thenAnswer(call->Duration.ofSeconds(300-elapsed.get()));
            readOnlyRepeatableRead().executeWithoutResult(status->{
                try(var cache=cache(candidates)) {
                    for(int index=0;index<days.size();index++) {
                        elapsed.set(List.of(0L,45L,121L).get(index));
                        assertThat(version(cache,days.get(index))).isEqualTo(expected.get(index));
                        assertThat(cache.hasRetainedDay()).isTrue();
                    }
                }
            });
            deadline.verify(()->CarbonCalculationDeadline.start(any()),never());
        }

        assertThat(observed).containsExactly(Duration.ofSeconds(30),Duration.ofSeconds(30),Duration.ofSeconds(30));
        verify(repository,times(3)).loadCurrentReviewedDay(eq(BUILDING),any(),any(),any());
        verify(repository,never()).electricityVersion(anyString(),any(),any(),anyString());
    }

    @Test void aRealCallerDeadlineIsNotResetWhenTheNextDayLoads() {
        seedDay(1,2);
        fact("next-day", "approved", "P", DAY_END, DAY_END.plusSeconds(7200), "{}", null);
        var candidates=new ArrayList<>(twoHours(FROM));candidates.addAll(twoHours(DAY_END));
        var left=new AtomicReference<>(Duration.ofSeconds(4));
        var observed=new ArrayList<Duration>();
        doAnswer(call->{
            java.util.function.Supplier<Duration> budget=call.getArgument(3);
            observed.add(budget.get());return call.callRealMethod();
        }).when(repository).loadCurrentReviewedDay(anyString(),any(),any(),any());
        clearInvocations(repository);

        try(var deadline=mockStatic(CarbonCalculationDeadline.class,CALLS_REAL_METHODS)) {
            deadline.when(CarbonCalculationDeadline::currentRemaining).thenAnswer(call->left.get());
            readOnlyRepeatableRead().executeWithoutResult(status->{
                try(var cache=cache(candidates)) {
                    version(cache,FROM);
                    left.set(Duration.ZERO);
                    assertThatThrownBy(()->version(cache,DAY_END))
                            .isInstanceOf(com.platform.framework.exception.BusinessException.class);
                }
            });
        }
        assertThat(observed).containsExactly(Duration.ofSeconds(4));
        verify(repository,times(1)).loadCurrentReviewedDay(eq(BUILDING),any(),any(),any());
        verify(repository,never()).electricityVersion(anyString(),any(),any(),anyString());
    }

    @Test void actualTransactionDeadlineStillBoundsEachDayRead() {
        seedDay(1,2);
        var observed=new ArrayList<Duration>();
        doAnswer(call->{
            java.util.function.Supplier<Duration> budget=call.getArgument(3);
            observed.add(budget.get());return call.callRealMethod();
        }).when(repository).loadCurrentReviewedDay(anyString(),any(),any(),any());
        readOnlyRepeatableRead().executeWithoutResult(status->{
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);
            holder.setTimeoutInMillis(10_000);
            try(var cache=cache(twoHours(FROM))) {version(cache,FROM);}
        });
        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test void sameDayCacheHitsDoNotBypassAnExpiredRealCallerDeadline() {
        seedDay(1,2);
        var left=new AtomicReference<>(Duration.ofSeconds(4));
        clearInvocations(repository);
        try(var deadline=mockStatic(CarbonCalculationDeadline.class,CALLS_REAL_METHODS)) {
            deadline.when(CarbonCalculationDeadline::currentRemaining).thenAnswer(call->left.get());
            readOnlyRepeatableRead().executeWithoutResult(status->{
                try(var cache=cache(twoHours(FROM))) {
                    version(cache,FROM);
                    assertThat(cache.hasRetainedDay()).isTrue();
                    left.set(Duration.ZERO);
                    assertThatThrownBy(()->version(cache,FROM.plusSeconds(3600)))
                            .isInstanceOf(com.platform.framework.exception.BusinessException.class);
                }
            });
        }
        verify(repository).loadCurrentReviewedDay(eq(BUILDING),eq(FROM),eq(DAY_END),any());
        verify(repository,never()).electricityVersion(anyString(),any(),any(),anyString());
    }

    @Test void sameDayCacheHitsDoNotBypassAnExpiredTransactionDeadline() {
        seedDay(1,2);
        clearInvocations(repository);
        readOnlyRepeatableRead().executeWithoutResult(status->{
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);
            try(var cache=cache(twoHours(FROM))) {
                version(cache,FROM);
                holder.setTimeoutInMillis(-1);
                assertThatThrownBy(()->version(cache,FROM.plusSeconds(3600)))
                        .isInstanceOf(org.springframework.transaction.TransactionTimedOutException.class);
                status.setRollbackOnly();
            }
        });
        verify(repository).loadCurrentReviewedDay(eq(BUILDING),eq(FROM),eq(DAY_END),any());
        verify(repository,never()).electricityVersion(anyString(),any(),any(),anyString());
    }

    @Test void aPositiveSubmillisecondRealDeadlineIsNotAnOptionalFallbackAtEntryOrOnHits() {
        seedDay(1,2);
        var left=new AtomicReference<>(Duration.ofNanos(500_000));
        clearInvocations(repository);
        try(var deadline=mockStatic(CarbonCalculationDeadline.class,CALLS_REAL_METHODS)) {
            deadline.when(CarbonCalculationDeadline::currentRemaining).thenAnswer(call->left.get());
            readOnlyRepeatableRead().executeWithoutResult(status->{
                try(var cache=cache(twoHours(FROM))) {
                    assertThatThrownBy(()->version(cache,FROM))
                            .isInstanceOf(com.platform.framework.exception.BusinessException.class);
                    verify(repository,never()).loadCurrentReviewedDay(anyString(),any(),any(),any());
                    left.set(Duration.ofSeconds(4));
                    version(cache,FROM);
                    left.set(Duration.ofNanos(500_000));
                    assertThatThrownBy(()->version(cache,FROM.plusSeconds(3600)))
                            .isInstanceOf(com.platform.framework.exception.BusinessException.class);
                }
            });
        }
        verify(repository).loadCurrentReviewedDay(eq(BUILDING),eq(FROM),eq(DAY_END),any());
        verify(repository,never()).electricityVersion(anyString(),any(),any(),anyString());
    }

    @Test void aRealDeadlineBecomingSubmillisecondBetweenSqlPhasesStillCannotFallBack() {
        seedDay(1,2);
        var left=new AtomicReference<>(Duration.ofSeconds(4));
        doAnswer(call->{
            var page=call.callRealMethod();left.set(Duration.ofNanos(500_000));return page;
        }).when(repository).currentReviewedDayCandidates(anyString(),any(),any(),any(),anyInt(),any());
        clearInvocations(repository);
        try(var deadline=mockStatic(CarbonCalculationDeadline.class,CALLS_REAL_METHODS)) {
            deadline.when(CarbonCalculationDeadline::currentRemaining).thenAnswer(call->left.get());
            assertThatThrownBy(()->readOnlyRepeatableRead().executeWithoutResult(status->{
                try(var cache=cache(twoHours(FROM))) {version(cache,FROM);}
            })).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        }
        verify(repository).loadCurrentReviewedDay(eq(BUILDING),eq(FROM),eq(DAY_END),any());
        verify(repository,never()).currentReviewedDayBodies(anyString(),any(),any(),anyList(),any());
        verify(repository,never()).electricityVersion(anyString(),any(),any(),anyString());
    }

    @Test void sqlErrorClosesTheRequestCacheAndDoesNotRetryOrReturnOldPathResults() {
        seedDay(1, 2);
        var failure = new QueryTimeoutException("day-read-failed");
        var retained = new AtomicReference<CurrentElectricityQueryVersions>();
        doThrow(failure).when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
        clearInvocations(repository);

        assertThatThrownBy(() -> readOnlyRepeatableRead().executeWithoutResult(status -> {
            try (var cache = cache(twoHours(FROM))) {
                retained.set(cache);
                version(cache, FROM);
            }
        })).isSameAs(failure);

        assertThat(retained.get().hasRetainedDay()).isFalse();
        assertThatThrownBy(() -> version(retained.get(), FROM)).hasMessageContaining("closed");
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
    }

    private CurrentElectricityQueryVersions cache(List<DynamicElectricityRepository.ChartResult> candidates) {
        return new CurrentElectricityQueryVersions(repository, USER, ROLES, BUILDING, candidates);
    }

    private String version(CurrentElectricityQueryVersions cache, Instant from) {
        return cache.version(USER, ROLES, BUILDING, from, from.plusSeconds(3600), REVIEWED);
    }
}
