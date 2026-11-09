package com.platform.carbon;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;

/** GET-only cooperative budget, paired with the query method's Spring transaction timeout. */
final class DynamicElectricityQueryBudget implements AutoCloseable {
    static final int SECONDS = 110;
    private static final ThreadLocal<DynamicElectricityQueryBudget> CURRENT = new ThreadLocal<>();
    private final DynamicElectricityQueryBudget previous;
    private final CarbonCalculationDeadline deadline;
    private final CarbonCalculationDeadline.Scope binding;
    private ConnectionHolder transaction;
    private final long maximumTransactionMillis;

    static DynamicElectricityQueryBudget open() {
        Duration timeout = Duration.ofSeconds(SECONDS);
        Duration caller = CarbonCalculationDeadline.currentRemaining();
        if (caller != null && caller.compareTo(timeout) < 0) timeout = caller;
        return new DynamicElectricityQueryBudget(CarbonCalculationDeadline.start(timeout));
    }

    // Package-private seam permits deterministic expiry tests without waiting for a real request.
    DynamicElectricityQueryBudget(CarbonCalculationDeadline deadline) {
        this.previous = CURRENT.get();
        this.deadline = Objects.requireNonNull(deadline);
        // A shorter inherited caller budget must also bound SQL, not just Java checks.
        this.maximumTransactionMillis = Math.min(SECONDS * 1000L, deadline.remaining().toMillis() + 1);
        this.binding = deadline.bind();
        CURRENT.set(this);
    }

    void requireTransactionBudget(DataSource source) {
        // Direct unit calls have no Spring interception and receive cooperative checks only.
        // Normal controller GETs pass through the timed @Transactional method.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return;
        Object resource = source == null ? null : TransactionSynchronizationManager.getResource(source);
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_REPEATABLE_READ)
                || !(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()
                || !holder.hasTimeout()) {
            throw new IllegalStateException("Dynamic GET requires a timed read-only repeatable-read transaction");
        }
        // REQUIRED can join an older transaction and ignore this method's timeout declaration.
        // Never silently widen the budget or mutate that caller's transaction deadline.
        if (holder.getTimeToLiveInMillis() > maximumTransactionMillis)
            throw new IllegalStateException("Joined dynamic GET transaction exceeds the query budget");
        transaction = holder;
        check();
    }

    static void check() {
        DynamicElectricityQueryBudget budget = CURRENT.get();
        if (budget == null) return; // POST and background workers have no GET scope.
        if (Thread.currentThread().isInterrupted()) throw CarbonCalculationDeadline.timeout();
        budget.deadline.requireRemaining();
        if (budget.transaction != null) budget.transaction.getTimeToLiveInMillis();
    }

    @Override public void close() {
        try { binding.close(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
