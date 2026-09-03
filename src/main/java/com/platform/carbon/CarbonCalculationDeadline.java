package com.platform.carbon;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;

import static com.platform.carbon.CarbonErrors.CALCULATION_TIMEOUT;
import static com.platform.carbon.CarbonErrors.error;

/**
 * 普通同步核算的请求级截止时间。
 *
 * <p>它只把剩余预算传播给单次短事务，不能把整次计算包入事务；耗时的活动读取和公式计算由调用方
 * 在事务外中断。线程本地上下文必须通过 {@link Scope} 成对清理，避免连接池工作线程复用已过期预算。</p>
 */
final class CarbonCalculationDeadline {
    private static final ThreadLocal<CarbonCalculationDeadline> CURRENT = new ThreadLocal<>();

    private final LocalDateTime deadlineAt;
    private final long deadlineNanos;

    private CarbonCalculationDeadline(LocalDateTime deadlineAt, long deadlineNanos) {
        this.deadlineAt = deadlineAt;
        this.deadlineNanos = deadlineNanos;
    }

    static CarbonCalculationDeadline start(Duration timeout) {
        return new CarbonCalculationDeadline(LocalDateTime.now().plus(timeout),
                System.nanoTime() + timeout.toNanos());
    }

    LocalDateTime deadlineAt() {
        return deadlineAt;
    }

    boolean expired() {
        return remaining().isZero();
    }

    Duration remaining() {
        long nanos = deadlineNanos - System.nanoTime();
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    void requireRemaining() {
        if (expired()) throw timeout();
    }

    Scope bind() {
        CarbonCalculationDeadline previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    static Duration currentRemaining() {
        CarbonCalculationDeadline deadline = CURRENT.get();
        return deadline == null ? null : deadline.remaining();
    }

    /** JDBC 仅支持整秒超时，取不超过剩余预算的秒数，最后不足一秒时由外层中断兜底。 */
    static int timeoutSeconds(Duration remaining) {
        if (remaining == null || remaining.isNegative() || remaining.isZero()) {
            throw timeout();
        }
        long seconds = remaining.toSeconds();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }

    static boolean causedByDatabaseTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof QueryTimeoutException || current instanceof SQLTimeoutException
                    || current instanceof TransactionTimedOutException) {
                return true;
            }
            // JDBC 超时可能同时关闭连接，Spring 的回滚失败会把原异常移到 applicationException。
            if (current instanceof TransactionSystemException transaction
                    && transaction.getApplicationException() != current
                    && causedByDatabaseTimeout(transaction.getApplicationException())) return true;
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1317
                    || "70100".equals(sql.getSQLState()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static RuntimeException timeout() {
        return error(504, CALCULATION_TIMEOUT, "碳计算超过约定超时");
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
