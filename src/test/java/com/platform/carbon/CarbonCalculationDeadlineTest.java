package com.platform.carbon;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class CarbonCalculationDeadlineTest {
    @Test
    void rollbackFailureDoesNotHideTheOriginalQueryTimeout() {
        var rollback = new TransactionSystemException("rollback failed", new SQLException("closed"));
        rollback.initApplicationException(new QueryTimeoutException("query timeout"));

        assertThat(CarbonCalculationDeadline.causedByDatabaseTimeout(rollback)).isTrue();
    }

    @Test
    void springTransactionDeadlineIsAQueryTimeoutEvenBeforeTheOverallDeadline() {
        assertThat(CarbonCalculationDeadline.causedByDatabaseTimeout(
                new TransactionTimedOutException("transaction deadline exceeded"))).isTrue();
    }

    @Test
    void unrelatedRollbackFailureIsNotMisclassifiedAsTimeout() {
        var rollback = new TransactionSystemException("rollback failed", new SQLException("closed"));
        rollback.initApplicationException(new IllegalStateException("invalid state"));

        assertThat(CarbonCalculationDeadline.causedByDatabaseTimeout(rollback)).isFalse();
    }
}
