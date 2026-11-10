package com.platform.carbon;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** A background check's bounded snapshot; no evidence or authorization survives into another scan. */
final class CurrentReviewedHourBatch {
    static final int MAX_HOURS = 24;
    static final int TRANSACTION_SECONDS = 20;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private CurrentReviewedHourBatch() { }

    record Versions(List<String> values, long expiresAtNanos) {
        Versions { values = List.copyOf(values); }
        boolean usable() { return usable(System.nanoTime()); }
        boolean usable(long nowNanos) { return nowNanos - expiresAtNanos < 0; }
        int remainingSeconds(long nowNanos) {
            long left = expiresAtNanos - nowNanos;
            return left <= 0 ? 0 : (int) Math.min(TRANSACTION_SECONDS, (left + 999_999_999L) / 1_000_000_000L);
        }
    }

    static boolean eligible(DynamicElectricityLiveRepository.Task task) {
        if (!"READY".equals(task.state()) || !"REVIEWED_INTERVAL".equals(task.sourceKind())
                || !"HOUR".equals(task.grain()) || task.to() - task.from() != 3_600_000L) return false;
        var start = Instant.ofEpochMilli(task.from()).atZone(ZONE);
        return start.getMinute() == 0 && start.getSecond() == 0 && start.getNano() == 0;
    }

    static boolean sameGroup(DynamicElectricityLiveRepository.Task first, DynamicElectricityLiveRepository.Task next) {
        return eligible(first) && eligible(next) && first.user() == next.user()
                && first.building().equals(next.building()) && day(first).equals(day(next));
    }

    private static LocalDate day(DynamicElectricityLiveRepository.Task task) {
        return Instant.ofEpochMilli(task.from()).atZone(ZONE).toLocalDate();
    }

    static Optional<Versions> read(DynamicElectricityRepository repository,
            PlatformTransactionManager manager, List<DynamicElectricityLiveRepository.Task> tasks,
            BiFunction<DynamicElectricityLiveRepository.Task, String, String> completeVersion) {
        if (tasks.size() < 2 || tasks.size() > MAX_HOURS) return Optional.empty();
        var first = tasks.getFirst();
        var starts = new HashSet<Long>();
        long cursor = first.from();
        for (var task : tasks) {
            if (!sameGroup(first, task) || !starts.add(task.from()) || task.from() != cursor) return Optional.empty();
            cursor = task.to();
        }
        var transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(TRANSACTION_SECONDS);
        long started = System.nanoTime();
        // No claim is held during this transaction. The caller must reauthorize and conditionally
        // claim the unchanged task after it closes; a group observes one snapshot, not per-task now.
        return transaction.execute(status -> {
            var dataSource = repository.currentReviewedDayDataSource();
            Object resource = dataSource == null ? null : TransactionSynchronizationManager.getResource(dataSource);
            if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                            TransactionDefinition.ISOLATION_REPEATABLE_READ)) return Optional.empty();
            Supplier<Duration> remaining = () -> {
                Duration left = Duration.ofSeconds(TRANSACTION_SECONDS).minusNanos(System.nanoTime() - started);
                if (holder.hasTimeout()) {
                    Duration transactionLeft = Duration.ofMillis(holder.getTimeToLiveInMillis());
                    if (transactionLeft.compareTo(left) < 0) left = transactionLeft;
                }
                return left;
            };
            // Read only the exact contiguous union. Loading the whole day for two due hours
            // would multiply raw I/O even though it reduces repeated queries for long facts.
            Instant from = Instant.ofEpochMilli(first.from());
            var snapshot = repository.loadCurrentReviewedDay(first.building(), from,
                    Instant.ofEpochMilli(tasks.getLast().to()), remaining);
            if (snapshot.isEmpty()) return Optional.empty();
            var versions = new ArrayList<String>(tasks.size());
            for (var task : tasks) {
                CurrentReviewedDayVersionRead.available(remaining);
                String source = snapshot.get().version(Instant.ofEpochMilli(task.from()), Instant.ofEpochMilli(task.to()));
                versions.add(completeVersion.apply(task, source));
            }
            CurrentReviewedDayVersionRead.available(remaining);
            return Optional.of(new Versions(versions, started + Duration.ofSeconds(TRANSACTION_SECONDS).toNanos()));
        });
    }
}
