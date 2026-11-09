package com.platform.energy.classification;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.QueryTimeoutException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonthlyReadingWindowTest {
    private static final String BUILDING = "B";
    private static final YearMonth MONTH = YearMonth.of(2024, 2);
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final java.util.function.Supplier<Duration> LIVE_BUDGET = () -> BUDGET;
    private static final MonthlyReadingWindow.Limits LIMITS = new MonthlyReadingWindow.Limits(2, 100, 100_000, 4096, 100);
    @TempDir Path temporary;

    @Test void independentDayUsesBoundedDayWindowAndPreservesWholeCrossDayFacts() throws Exception {
        var crossing=fact("cross",start(1),start(3));
        var daily=fact("daily",start(2),start(3));
        var repository=repository(List.of(crossing,daily));
        Path directory;
        try(var window=MonthlyReadingWindow.prepareDay(repository,BUILDING,MONTH.atDay(2),LIVE_BUDGET)) {
            directory=window.directory();
            assertThat(read(window,2)).containsExactly(crossing.reading(),daily.reading());
            assertThatThrownBy(()->read(window,1)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->read(window,3)).isInstanceOf(BusinessException.class);
            verify(repository).overlappingWindowCandidatesPage(BUILDING,start(2),start(3),null,null,1000,LIVE_BUDGET);
            verify(repository).currentApprovedWindowByIds(BUILDING,start(2),start(3),List.of("cross","daily"),LIVE_BUDGET);
        }
        assertThat(Files.exists(directory)).isFalse();
    }

    @Test void preparationTimingIsAggregatedAndDoesNotChangeDailyReadsOrClose() throws Exception {
        try(var logs=new ScanLogs()) {
            var a=fact("private-raw-marker",start(1),start(2));
            var window=prepare(repository(List.of(a)),LIMITS);
            assertThat(read(window,1)).containsExactly(a.reading());
            assertThat(read(window,1)).containsExactly(a.reading());
            window.close();window.close();
            String message=logs.onlyScan();
            assertThat(message).contains("scanCompleted=true", "closeCompleted=true", "lastScanPhase=finalBudget",
                    "payloadBatches=1", "payloadRows=1", "spooledRows=1").doesNotContain("private-raw-marker");
            assertTimingFields(message);
            assertClean();
        }
    }

    @Test void preparationTimingKeepsOriginalPayloadFailureAndRecordsPartialProgress() throws Exception {
        try(var logs=new ScanLogs()) {
            var repository=repository(List.of(fact("a",start(1),start(2)),fact("b",start(1),start(2)),fact("c",start(1),start(2))));
            var original=new IllegalStateException("private-error-marker");
            doThrow(original).when(repository).currentApprovedWindowByIds(BUILDING,start(1),nextMonth(),List.of("c"),LIVE_BUDGET);
            assertThatThrownBy(()->prepare(repository,LIMITS)).isSameAs(original);
            String message=logs.onlyScan();
            assertThat(message).contains("scanCompleted=false", "closeCompleted=true", "lastScanPhase=payloadCall",
                    "payloadBatches=2", "payloadRows=2", "spooledRows=2").doesNotContain("private-error-marker");
            assertTimingFields(message);
            assertClean();
        }
    }

    @Test void preparationTimingKeepsOriginalMetadataFailureAndClosesSpools() throws Exception {
        try(var logs=new ScanLogs()) {
            var repository=repository(List.of());
            var original=new QueryTimeoutException("private-timeout-marker");
            doThrow(original).when(repository).overlappingWindowCandidatesPage(anyString(),any(),any(),nullable(Instant.class),nullable(String.class),anyInt(),any());
            assertThatThrownBy(()->prepare(repository,LIMITS)).isSameAs(original);
            assertThat(logs.onlyScan()).contains("scanCompleted=false", "closeCompleted=true", "lastScanPhase=metadataCall",
                    "payloadCallNanos=0", "encodeNanos=0", "spoolRouteWriteNanos=0").doesNotContain("private-timeout-marker");
            assertClean();
        }
    }

    @Test void preparationTimingRecordsEncodeCapacityFailureWithoutMaskingItsCode() throws Exception {
        try(var logs=new ScanLogs()) {
            var repository=repository(List.of(fact("a",start(1),start(2))));
            assertCapacity(repository,new MonthlyReadingWindow.Limits(2,100,100_000,8,100));
            assertThat(logs.onlyScan()).contains("scanCompleted=false", "closeCompleted=true", "lastScanPhase=encode",
                    "payloadRows=1", "spooledRows=0", "spoolRouteWriteNanos=0");
            assertClean();
        }
    }

    private static void assertTimingFields(String message) {
        for(String name:List.of("metadataCallNanos","payloadCallNanos","encodeNanos","spoolRouteWriteNanos","spoolCloseNanos","scanThroughCloseNanos"))
            assertThat(message).containsPattern(name+"=\\d+(?: |$)");
    }

    private static final class ScanLogs implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(MonthlyReadingWindow.class);
        private final ch.qos.logback.classic.Level previous=logger.getLevel();
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<>();
        ScanLogs(){appender.start();logger.addAppender(appender);logger.setLevel(ch.qos.logback.classic.Level.INFO);}
        String onlyScan(){
            var messages=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(message->message.startsWith("Daily source scan ")).toList();
            assertThat(messages).hasSize(1);return messages.getFirst();
        }
        @Override public void close(){logger.detachAppender(appender);logger.setLevel(previous);appender.stop();}
    }

    @Test void scansEachCursorPageOnlyOnceAndNeverQueriesAgainForDailyReads() throws Exception {
        var a = fact("a", start(1), start(2));
        var b = fact("b", start(1), start(2));
        var c = fact("c", start(29), nextMonth());
        var repository = repository(List.of(a, b, c));
        try (var window = prepare(repository, LIMITS)) {
            assertThat(read(window, 1)).containsExactly(a.reading(), b.reading());
            for (int day = 2; day < 29; day++) assertThat(read(window, day)).isEmpty();
            assertThat(read(window, 29)).containsExactly(c.reading());
            assertThat(read(window, 1)).containsExactly(a.reading(), b.reading());
            try (var files = Files.list(window.directory())) { assertThat(files.count()).isEqualTo(29); }
            assertThat(window.metrics().metadataRows()).isEqualTo(3);
            assertThat(window.metrics().candidateIds()).isEqualTo(3);
            assertThat(window.metrics().payloadBatches()).isEqualTo(2);
        }
        var ordered = inOrder(repository);
        ordered.verify(repository).populateMonthlyWindow(any(MonthlyReadingWindow.class));
        ordered.verify(repository).overlappingWindowCandidatesPage(BUILDING, start(1), nextMonth(), null, null, 2, LIVE_BUDGET);
        ordered.verify(repository).overlappingWindowCandidatesPage(BUILDING, start(1), nextMonth(), start(1), "b", 2, LIVE_BUDGET);
        ordered.verify(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("a", "b"), LIVE_BUDGET);
        ordered.verify(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("c"), LIVE_BUDGET);
        verifyNoMoreInteractions(repository);
        assertClean();
    }

    @Test void fullFinalPageRequiresExactlyOneEmptyCursorPage() throws Exception {
        var repository = repository(List.of(fact("a", start(1), start(2)), fact("b", start(2), start(3))));
        try (var window = prepare(repository, LIMITS)) { assertThat(read(window, 2)).hasSize(1); }
        verify(repository, times(2)).overlappingWindowCandidatesPage(eq(BUILDING), eq(start(1)), eq(nextMonth()), nullable(Instant.class), nullable(String.class), eq(2), eq(LIVE_BUDGET));
        verify(repository).overlappingWindowCandidatesPage(BUILDING, start(1), nextMonth(), start(2), "b", 2, LIVE_BUDGET);
    }

    @Test void duplicatesOriginalFactsIntoAllAndOnlyIntersectingDaysAcrossMonthBoundaries() {
        var entering = fact("entering", start(1).minusSeconds(3600), start(2));
        var crossing = fact("crossing", start(2).minusSeconds(1), start(3));
        var leaving = fact("leaving", start(29), nextMonth().plusSeconds(3600));
        var repository = repository(List.of(entering, crossing, leaving));
        var limits = new MonthlyReadingWindow.Limits(4, 100, 100_000, 4096, 100);
        try (var window = prepare(repository, limits)) {
            assertThat(read(window, 1)).containsExactly(entering.reading(), crossing.reading());
            assertThat(read(window, 2)).containsExactly(crossing.reading());
            for (int day = 3; day < 29; day++) assertThat(read(window, day)).isEmpty();
            assertThat(read(window, 29)).containsExactly(leaving.reading());
        }
    }

    @Test void preservesRawJsonDecimalScaleNullAndDatabaseMillisecondPrecision() {
        var raw = " {\"source\":\"\\u7535\",\"quantity\":1.2300,\"unknown\":[null, true]}\n";
        var precise = new ClassificationRepository.StoredReading("precise", "batch-x", raw, new BigDecimal("12345678901234567890.123456789000"), 42L, "APPROVED", start(1).plusMillis(123));
        var absent = new ClassificationRepository.StoredReading("absent", "batch-y", "{\"quantity\":null}", null, 7L, "APPROVED", start(1).plusSeconds(1));
        var repository = repository(List.of(new ClassificationRepository.WindowReading(precise, start(2)), new ClassificationRepository.WindowReading(absent, start(2))));
        try (var window = prepare(repository, LIMITS)) {
            assertThat(read(window, 1)).containsExactly(precise, absent);
            assertThat(read(window, 1).getFirst().quantity().scale()).isEqualTo(precise.quantity().scale());
        }
    }

    @Test void appliesDailySealedExclusionBeforeRetainedLimitWithoutMutatingOtherReads() {
        var sealed = fact("sealed", start(1), start(3));
        var live = fact("live", start(1), start(3));
        var repository = repository(List.of(sealed, live));
        var limits = new MonthlyReadingWindow.Limits(2, 100, 100_000, 4096, 1);
        try (var window = prepare(repository, limits)) {
            assertThat(window.readDay(BUILDING, start(1), start(2), Set.of("sealed"))).containsExactly(live.reading());
            assertThatThrownBy(() -> read(window, 2)).isInstanceOf(BusinessException.class);
            assertThat(window.readDay(BUILDING, start(2), start(3), Set.of("live"))).containsExactly(sealed.reading());
        }
    }

    @Test void rejectsWrongBuildingMonthAndNonDailyBounds() {
        try (var window = prepare(repository(List.of()), LIMITS)) {
            assertThatThrownBy(() -> window.readDay("OTHER", start(1), start(2), Set.of())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> window.readDay(BUILDING, nextMonth(), nextMonth().plusSeconds(86400), Set.of())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> window.readDay(BUILDING, start(1).plusSeconds(1), start(2), Set.of())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> window.readDay(BUILDING, start(1), start(3), Set.of())).isInstanceOf(BusinessException.class);
        }
    }

    @Test void closeRemovesRequestDirectoryIsIdempotentAndDisallowsFurtherReads() throws Exception {
        var window = prepare(repository(List.of(fact("a", start(1), start(2)))), LIMITS);
        Path directory = window.directory();
        assertThat(directory).exists();
        window.close();
        window.close();
        assertThat(directory).doesNotExist();
        assertThatThrownBy(() -> read(window, 1)).isInstanceOf(IllegalStateException.class);
        assertClean();
    }

    @Test void repositoryFailureAfterWrittenPageCleansEverySpool() throws Exception {
        var repository = repository(List.of(fact("a", start(1), start(2)), fact("b", start(2), start(3)), fact("c", start(3), start(4))));
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("c"), LIVE_BUDGET);
        assertThatThrownBy(() -> prepare(repository, LIMITS)).isSameAs(failure);
        assertClean();
    }

    @Test void expiredBudgetDuringPreparationCleansSpools() throws Exception {
        var expired = new AtomicBoolean();
        var repository = scanRepository();
        when(repository.overlappingWindowCandidatesPage(anyString(), any(), any(), nullable(Instant.class), nullable(String.class), anyInt(), any())).thenAnswer(call -> {
            expired.set(true);
            return List.of(candidate(fact("a", start(1), start(2))));
        });
        assertThatThrownBy(() -> MonthlyReadingWindow.prepare(repository, BUILDING, MONTH, () -> expired.get() ? Duration.ZERO : BUDGET, LIMITS, temporary)).isInstanceOf(QueryTimeoutException.class);
        assertClean();
    }

    @Test void interruptionDuringPreparationCleansSpoolsAndPreservesInterruptFlag() throws Exception {
        var repository = scanRepository();
        when(repository.overlappingWindowCandidatesPage(anyString(), any(), any(), nullable(Instant.class), nullable(String.class), anyInt(), any())).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            return List.of(candidate(fact("a", start(1), start(2))));
        });
        try {
            assertThatThrownBy(() -> prepare(repository, LIMITS)).isInstanceOf(QueryTimeoutException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertClean();
    }

    @Test void enforcesOverlappingCandidateLimitEvenWhenRowsWouldBeSealedLater() throws Exception {
        var repository = repository(List.of(fact("a", start(1), start(2)), fact("b", start(2), start(3))));
        assertCapacity(repository, new MonthlyReadingWindow.Limits(2, 1, 100_000, 4096, 100));
        assertClean();
    }

    @Test void enforcesEncodedRowByteLimitAndCleansSpools() throws Exception {
        assertCapacity(repository(List.of(fact("a", start(1), start(2)))), new MonthlyReadingWindow.Limits(2, 100, 100_000, 8, 100));
        assertClean();
    }

    @Test void totalByteLimitCountsEveryDailyCopyIncludingFraming() throws Exception {
        var single = fact("a", start(1), start(2));
        long oneDayBytes;
        try (var window = prepare(repository(List.of(single)), LIMITS)) { oneDayBytes = Files.size(window.directory().resolve("day-1")); }
        var exact = new MonthlyReadingWindow.Limits(2, 100, oneDayBytes, 4096, 100);
        try (var window = prepare(repository(List.of(single)), exact)) { assertThat(read(window, 1)).hasSize(1); }
        assertCapacity(repository(List.of(fact("a", start(1), start(3)))), exact);
        assertClean();
    }

    @Test void overlappingRequestsHaveDistinctDirectoriesAndIndependentCleanup() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var repository = repository(List.of(fact("a", start(1), start(2))));
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test synchronization timed out");
            return List.of(candidate(fact("a", start(1), start(2))));
        }).when(repository).overlappingWindowCandidatesPage(anyString(), any(), any(), nullable(Instant.class), nullable(String.class), anyInt(), any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> prepare(repository, LIMITS));
            var second = executor.submit(() -> prepare(repository, LIMITS));
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            try (var left = first.get(5, TimeUnit.SECONDS); var right = second.get(5, TimeUnit.SECONDS)) {
                assertThat(left.directory()).isNotEqualTo(right.directory());
                left.close();
                assertThat(right.directory()).exists();
                assertThat(read(right, 1)).containsExactly(fact("a", start(1), start(2)).reading());
            }
        }
        assertClean();
    }

    @Test void truncatedSpoolIsRejectedRatherThanReturningPartialRows() throws Exception {
        try (var window = prepare(repository(List.of(fact("a", start(1), start(2)))), LIMITS)) {
            Path day = window.directory().resolve("day-1");
            byte[] bytes = Files.readAllBytes(day);
            Files.write(day, Arrays.copyOf(bytes, bytes.length - 1));
            assertThatThrownBy(() -> read(window, 1)).isInstanceOf(BusinessException.class);
        }
        assertClean();
    }

    @Test void repeatedCursorFailsClosedInsteadOfLoopingAndCleansSpools() throws Exception {
        var repository = scanRepository();
        var page = List.of(candidate(fact("a", start(1).minusSeconds(2), start(1))), candidate(fact("b", start(1).minusSeconds(1), start(1))));
        when(repository.overlappingWindowCandidatesPage(anyString(), any(), any(), nullable(Instant.class), nullable(String.class), anyInt(), any())).thenReturn(page);
        assertThatThrownBy(() -> prepare(repository, LIMITS)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo("DAILY_SOURCE_INTEGRITY");
        verify(repository, times(2)).overlappingWindowCandidatesPage(eq(BUILDING), eq(start(1)), eq(nextMonth()), nullable(Instant.class), nullable(String.class), eq(2), eq(LIVE_BUDGET));
        verify(repository, never()).currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any());
        assertClean();
    }

    @Test void expiredBudgetBeforeScanAvoidsDatabaseAndLeavesNoDirectory() throws Exception {
        var repository = scanRepository();
        assertThatThrownBy(() -> MonthlyReadingWindow.prepare(repository, BUILDING, MONTH, () -> Duration.ZERO, LIMITS, temporary))
                .isInstanceOf(QueryTimeoutException.class);
        verify(repository).populateMonthlyWindow(any(MonthlyReadingWindow.class));
        verifyNoMoreInteractions(repository);
        assertClean();
    }

    @Test void retainedByteBudgetIsCheckedAfterSealingAndBeforeRetainingDecodedRows() throws Exception {
        var a=fact("a",start(1),start(2));var b=fact("b",start(1),start(2));long encodedBytes;
        try(var window=prepare(repository(List.of(a)),LIMITS)) {encodedBytes=Files.size(window.directory().resolve("day-1"))-2L*Integer.BYTES;}
        var limits=new MonthlyReadingWindow.Limits(2,100,100_000,4096,100,encodedBytes);
        try(var window=prepare(repository(List.of(a,b)),limits)) {
            assertThatThrownBy(()->read(window,1)).isInstanceOf(BusinessException.class)
                    .extracting(error->((BusinessException)error).getErrorCode()).isEqualTo("DAILY_SOURCE_CAPACITY");
            assertThat(window.readDay(BUILDING,start(1),start(2),Set.of("a"))).containsExactly(b.reading());
            assertThat(window.readDay(BUILDING,start(1),start(2),Set.of("a","b"))).isEmpty();
        }
        assertClean();
    }

    @Test void currentMonthScanAndDailyCopiesStopBeforeUnendedDays() throws Exception {
        var cross = fact("cross", start(2), nextMonth());
        var repository = repository(List.of(cross));
        Path directory;
        try (var window = MonthlyReadingWindow.prepare(repository, BUILDING, MONTH, MONTH.atDay(3), LIVE_BUDGET)) {
            directory = window.directory();
            assertThat(read(window, 2)).containsExactly(cross.reading());
            assertThat(read(window, 3)).isEmpty();
            assertThat(Files.size(directory.resolve("day-3"))).isZero();
        }
        verify(repository).overlappingWindowCandidatesPage(BUILDING, start(1), start(3), null, null, 1000, LIVE_BUDGET);
        verify(repository).currentApprovedWindowByIds(BUILDING, start(1), start(3), List.of("cross"), LIVE_BUDGET);
        assertThat(directory).doesNotExist();
    }

    @Test void fullFrameRemovalAndSameLengthContentCorruptionAreRejected() throws Exception {
        try(var window=prepare(repository(List.of(fact("a",start(1),start(2)))),LIMITS)) {
            var day=window.directory().resolve("day-1");byte[] original=Files.readAllBytes(day);
            Files.write(day,new byte[0]);assertThatThrownBy(()->read(window,1)).isInstanceOf(BusinessException.class);
            byte[] changed=original.clone();changed[changed.length-1]^=1;Files.write(day,changed);
            assertThatThrownBy(()->read(window,1)).isInstanceOf(BusinessException.class);
        }
        assertClean();
    }

    @Test void monthlyTransactionCompletionFailureCannotLeakPreparationDirectory() throws Exception {
        var repository=repository(List.of(fact("a",start(1),start(2))));var committed=new AtomicBoolean();
        var manager=new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){}
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                committed.set(true);throw new org.springframework.transaction.TransactionSystemException("simulated completion failure");
            }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
        };
        var factory=new org.springframework.aop.framework.ProxyFactory(repository);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        assertThatThrownBy(()->prepare((ClassificationRepository)factory.getProxy(),LIMITS))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(committed).isTrue();assertClean();
    }

    @Test void allCursorPagesShareOneReadOnlyRepeatableReadTransaction() throws Exception {
        var facts = List.of(fact("a", start(1), start(2)), fact("b", start(2), start(3)), fact("c", start(3), start(4)), fact("d", start(4), start(5)));
        var manager = new RecordingTransactionManager();
        var transactions = new ArrayList<Object>();
        Runnable observe = () -> { assertMonthlyTransaction(manager); transactions.add(manager.active); };
        var repository = repository(facts, observe);
        try (var window = prepare(transactional(repository, manager), LIMITS)) {
            assertThat(manager.begins).isEqualTo(1);
            assertThat(manager.commits).isEqualTo(1);
            assertThat(manager.rollbacks).isZero();
            assertThat(transactions).hasSize(5);
            assertThat(transactions).allSatisfy(transaction -> assertThat(transaction).isSameAs(transactions.getFirst()));
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(read(window, 1)).containsExactly(facts.getFirst().reading());
            assertThat(read(window, 4)).containsExactly(facts.getLast().reading());
        }
        verify(repository, times(3)).overlappingWindowCandidatesPage(eq(BUILDING), eq(start(1)), eq(nextMonth()), nullable(Instant.class), nullable(String.class), eq(2), eq(LIVE_BUDGET));
        verify(repository, times(2)).currentApprovedWindowByIds(eq(BUILDING), eq(start(1)), eq(nextMonth()), anyList(), eq(LIVE_BUDGET));
        assertClean();
    }

    @Test void budgetExhaustionOnLaterPageRollsBackSingleMonthlyTransactionAndCleansSpools() throws Exception {
        var manager = new RecordingTransactionManager();
        var expired = new AtomicBoolean();
        var facts = List.of(fact("a", start(1), start(2)), fact("b", start(2), start(3)), fact("c", start(3), start(4)));
        var repository = repository(facts, () -> assertMonthlyTransaction(manager));
        doAnswer(call -> {
            assertMonthlyTransaction(manager);
            expired.set(true);
            return List.of(facts.getLast());
        }).when(repository).currentApprovedWindowByIds(eq(BUILDING), eq(start(1)), eq(nextMonth()), eq(List.of("c")), any());
        assertThatThrownBy(() -> MonthlyReadingWindow.prepare(transactional(repository, manager), BUILDING, MONTH,
                () -> expired.get() ? Duration.ZERO : BUDGET, LIMITS, temporary)).isInstanceOf(QueryTimeoutException.class);
        verify(repository, times(2)).overlappingWindowCandidatesPage(anyString(), any(), any(), nullable(Instant.class), nullable(String.class), anyInt(), any());
        verify(repository, times(2)).currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any());
        assertThat(manager.begins).isEqualTo(1);
        assertThat(manager.commits).isZero();
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertClean();
    }

    @Test void primaryKeyFetchOrderDoesNotChangeOriginalDailyTimeOrder() throws Exception {
        var earlier = fact("z", start(1), start(1).plusSeconds(1));
        var middle = fact("m", start(1).plusSeconds(1), start(1).plusSeconds(2));
        var later = fact("a", start(1).plusSeconds(2), start(2));
        var repository = repository(List.of(earlier, middle, later));
        try (var window = prepare(repository, LIMITS)) {
            assertThat(read(window, 1)).containsExactly(earlier.reading(), middle.reading(), later.reading());
            assertThat(window.readDay(BUILDING, start(1), start(2), Set.of("m")))
                    .containsExactly(earlier.reading(), later.reading());
        }
        var ordered = inOrder(repository);
        ordered.verify(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("a", "m"), LIVE_BUDGET);
        ordered.verify(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("z"), LIVE_BUDGET);
        assertClean();
    }

    @Test void historicalNonOverlapIsFilteredBeforePaginationWithoutFetchingItsPayload() throws Exception {
        var oldA = fact("old-a", start(1).minusSeconds(86400), start(1));
        var oldB = fact("old-b", start(1).minusSeconds(3600), start(1));
        var live = fact("live", start(1), start(2));
        var repository = repository(List.of(oldA, oldB, live));
        try (var window = prepare(repository, LIMITS)) {
            assertThat(read(window, 1)).containsExactly(live.reading());
            assertThat(window.metrics().metadataRows()).isEqualTo(1);
            assertThat(window.metrics().candidateIds()).isEqualTo(1);
            assertThat(window.metrics().payloadRows()).isEqualTo(1);
        }
        verify(repository).overlappingWindowCandidatesPage(BUILDING, start(1), nextMonth(), null, null, 2, LIVE_BUDGET);
        verify(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("live"), LIVE_BUDGET);
        verify(repository, times(1)).currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any());
        assertClean();
    }

    @Test void exclusivelyHistoricalWindowReturnsOneEmptyMetadataPageAndNoJson() throws Exception {
        var history = new ArrayList<ClassificationRepository.WindowReading>();
        for (int index = 0; index < 7; index++) {
            history.add(fact("history-" + index, start(1).minusSeconds(100 - index), start(1)));
        }
        var repository = repository(history);
        var limits = new MonthlyReadingWindow.Limits(2, 1, 100_000, 4096, 100);
        try (var window = prepare(repository, limits)) {
            assertThat(window.metrics().metadataRows()).isZero();
            assertThat(window.metrics().candidateIds()).isZero();
            assertThat(window.metrics().identifierBytes()).isZero();
            assertThat(window.metrics().payloadBatches()).isZero();
            assertThat(read(window, 1)).isEmpty();
        }
        verify(repository, times(1)).overlappingWindowCandidatesPage(anyString(), any(), any(), nullable(Instant.class), nullable(String.class), eq(2), any());
        verify(repository, never()).currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any());
        assertClean();
    }

    @Test void emptyUnapprovedPayloadBatchStillAdvancesEveryCandidateIdAndTerminates() throws Exception {
        var a = fact("a", start(1), start(2));
        var b = fact("b", start(1), start(2));
        var c = fact("c", start(1), start(2));
        var repository = repository(List.of(a, b, c));
        // The SQL layer returns an approved subset; an empty subset must not be treated as pagination completion.
        doReturn(List.of()).when(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("a", "b"), LIVE_BUDGET);
        try (var window = prepare(repository, LIMITS)) {
            assertThat(read(window, 1)).containsExactly(c.reading());
            assertThat(window.metrics().candidateIds()).isEqualTo(3);
            assertThat(window.metrics().payloadBatches()).isEqualTo(2);
            assertThat(window.metrics().payloadRows()).isEqualTo(1);
        }
        var ordered = inOrder(repository);
        ordered.verify(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("a", "b"), LIVE_BUDGET);
        ordered.verify(repository).currentApprovedWindowByIds(BUILDING, start(1), nextMonth(), List.of("c"), LIVE_BUDGET);
        verify(repository, times(2)).currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any());
        assertClean();
    }

    @Test void duplicatePayloadIdentityFailsIntegrityAndCleansSpools() throws Exception {
        var a = fact("a", start(1), start(2));
        var repository = repository(List.of(a, fact("b", start(1), start(2))));
        doReturn(List.of(a, a)).when(repository).currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any());
        assertIntegrity(repository);
        assertClean();
    }

    @Test void unrequestedPayloadIdentityFailsIntegrityAndCleansSpools() throws Exception {
        var repository = repository(List.of(fact("a", start(1), start(2))));
        doReturn(List.of(fact("other", start(1), start(2)))).when(repository)
                .currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any());
        assertIntegrity(repository);
        assertClean();
    }

    private void assertIntegrity(ClassificationRepository repository) {
        assertThatThrownBy(() -> prepare(repository, LIMITS)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo("DAILY_SOURCE_INTEGRITY");
    }

    private static ClassificationRepository scanRepository() {
        var repository = mock(ClassificationRepository.class);
        try { doCallRealMethod().when(repository).populateMonthlyWindow(any(MonthlyReadingWindow.class)); }
        catch (IOException exception) { throw new AssertionError("Stubbing must not execute the scan", exception); }
        return repository;
    }

    private static ClassificationRepository transactional(ClassificationRepository repository, RecordingTransactionManager manager) {
        var factory = new org.springframework.aop.framework.ProxyFactory(repository);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (ClassificationRepository) factory.getProxy();
    }

    private static void assertMonthlyTransaction(RecordingTransactionManager manager) {
        assertThat(manager.begins).isEqualTo(1);
        assertThat(manager.commits).isZero();
        assertThat(manager.active).isNotNull();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private static final class RecordingTransactionManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        private int begins;
        private int commits;
        private int rollbacks;
        private Object active;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getPropagationBehavior()).isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            begins++;
            active = transaction;
        }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rollbacks++; }
    }

    private MonthlyReadingWindow prepare(ClassificationRepository repository, MonthlyReadingWindow.Limits limits) {
        return MonthlyReadingWindow.prepare(repository, BUILDING, MONTH, LIVE_BUDGET, limits, temporary);
    }
    private ClassificationRepository repository(List<ClassificationRepository.WindowReading> facts) {
        return repository(facts, () -> { });
    }

    private ClassificationRepository repository(List<ClassificationRepository.WindowReading> facts, Runnable onQuery) {
        var repository = scanRepository();
        // These fixtures model phase inputs, not SQL approval/supersession authority (tested at the repository).
        when(repository.overlappingWindowCandidatesPage(anyString(), any(), any(), nullable(Instant.class), nullable(String.class), anyInt(), any()))
                .thenAnswer(call -> {
                    onQuery.run();
                    Instant from = call.getArgument(1);
                    var candidates = facts.stream().filter(fact -> fact.endExclusive()==null || fact.endExclusive().isAfter(from)).toList();
                    Instant cursor = call.getArgument(3);
                    String id = call.getArgument(4);
                    int offset = 0;
                    if (cursor != null) {
                        while (offset < candidates.size() && !(candidates.get(offset).reading().startInclusive().equals(cursor)
                                && candidates.get(offset).reading().id().equals(id))) offset++;
                        if (offset == candidates.size()) throw new AssertionError("Unknown metadata cursor");
                        offset++;
                    }
                    int limit = call.getArgument(5);
                    return candidates.subList(offset, Math.min(offset + limit, candidates.size())).stream().map(MonthlyReadingWindowTest::candidate).toList();
                });
        when(repository.currentApprovedWindowByIds(anyString(), any(), any(), anyList(), any())).thenAnswer(call -> {
            onQuery.run();
            List<String> ids = call.getArgument(3);
            return ids.stream().flatMap(id -> facts.stream().filter(fact -> fact.reading().id().equals(id))).toList();
        });
        return repository;
    }

    private static ClassificationRepository.WindowCandidate candidate(ClassificationRepository.WindowReading fact) {
        return new ClassificationRepository.WindowCandidate(fact.reading().id(), fact.reading().startInclusive(), fact.endExclusive());
    }
    private static ClassificationRepository.WindowReading fact(String id, Instant from, Instant to) {
        return new ClassificationRepository.WindowReading(new ClassificationRepository.StoredReading(id, "batch", "{\"fact\":\"" + id + "\"}", new BigDecimal("1.2300"), 9L, "APPROVED", from), to);
    }
    private static List<ClassificationRepository.StoredReading> read(MonthlyReadingWindow window, int day) {
        return window.readDay(BUILDING, start(day), start(day).plusSeconds(86400), Set.of());
    }
    private static Instant start(int day) { return MONTH.atDay(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    private static Instant nextMonth() { return MONTH.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    private void assertCapacity(ClassificationRepository repository, MonthlyReadingWindow.Limits limits) {
        assertThatThrownBy(() -> prepare(repository, limits)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo("DAILY_SOURCE_CAPACITY");
    }
    private void assertClean() throws IOException {
        try (var entries = Files.list(temporary)) { assertThat(entries.toList()).isEmpty(); }
    }
}
