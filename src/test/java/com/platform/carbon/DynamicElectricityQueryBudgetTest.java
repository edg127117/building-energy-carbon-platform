package com.platform.carbon;

import com.platform.audit.AuditGovernanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DynamicElectricityQueryBudgetTest {
    @Test void absentGetScopeDoesNotChangePostOrWorkerDeadlineBehavior() {
        try (var ignored = CarbonCalculationDeadline.start(Duration.ZERO).bind()) {
            assertThatCode(DynamicElectricityQueryBudget::check).doesNotThrowAnyException();
        }
    }
    @Test void expiredScopeFailsAndRestoresPriorContextOnClose() {
        var previous = CarbonCalculationDeadline.start(Duration.ofMinutes(3));
        try (var ignored = previous.bind()) {
            try (var budget = new DynamicElectricityQueryBudget(CarbonCalculationDeadline.start(Duration.ZERO))) {
                assertThatThrownBy(DynamicElectricityQueryBudget::check).isInstanceOf(RuntimeException.class);
            }
            assertThat(CarbonCalculationDeadline.currentRemaining()).isGreaterThan(Duration.ofSeconds(110));
            assertThatCode(DynamicElectricityQueryBudget::check).doesNotThrowAnyException();
        }
        assertThat(CarbonCalculationDeadline.currentRemaining()).isNull();
    }
    @Test void shorterExistingCallerBudgetIsNeverRenewed() {
        try (var ignored = CarbonCalculationDeadline.start(Duration.ZERO).bind();
             var budget = DynamicElectricityQueryBudget.open()) {
            assertThatThrownBy(DynamicElectricityQueryBudget::check).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void interruptionFailsWithoutClearingInterruptFlagAndScopeCloses() {
        try (var budget = DynamicElectricityQueryBudget.open()) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(DynamicElectricityQueryBudget::check).isInstanceOf(RuntimeException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(CarbonCalculationDeadline.currentRemaining()).isNull();
    }
    @Test void annotationAppliesOnlyToNormalQueryAndKeepsReadOnlyRepeatableRead() throws Exception {
        var annotation = DynamicElectricityService.class.getMethod("query", long.class, java.util.Collection.class,
                String.class, String.class, String.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(annotation.timeout()).isEqualTo(110);
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        assertThat(DynamicElectricityService.class.getMethod("calculate", long.class, java.util.Collection.class,
                String.class, DynamicElectricityContracts.CalculationRequest.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
    }
    @Test void actualServiceProxyPropagatesDecreasingTransactionTimeoutToEveryJdbcStatement() throws Exception {
        var f = new Fixture();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            f.jdbc.execute("SELECT 1");
            var holder = (ConnectionHolder)TransactionSynchronizationManager.getResource(f.source);
            // Model elapsed transaction time deterministically, not a new timeout per statement.
            holder.setTimeoutInMillis(2000);
            f.jdbc.execute("SELECT 2");
            return List.of();
        }).when(f.repository).results(anyString(), any(), any());
        assertThat(f.query().points()).hasSize(24);
        var timeouts = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(f.statement, times(2)).setQueryTimeout(timeouts.capture());
        assertThat(timeouts.getAllValues().getFirst()).isBetween(100, 110);
        assertThat(timeouts.getAllValues().getLast()).isBetween(1, 2);
        verify(f.connection).commit(); verify(f.connection).close();
        assertThat(TransactionSynchronizationManager.hasResource(f.source)).isFalse();
        assertThat(CarbonCalculationDeadline.currentRemaining()).isNull();
    }
    @Test void joinedUntimedOrOverlongTransactionFailsBeforeAnyBusinessRead() throws Exception {
        for (int timeout : new int[]{TransactionDefinition.TIMEOUT_DEFAULT, 120}) {
            var f = new Fixture(); var tx = f.transaction(timeout, true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(() -> tx.execute(status -> f.query())).isInstanceOf(IllegalStateException.class);
            verify(f.repository, never()).results(anyString(), any(), any());
            verify(f.connection).rollback();
            assertThat(CarbonCalculationDeadline.currentRemaining()).isNull();
        }
    }
    @Test void joinedWrongIsolationOrWritableTransactionFailsClosed() throws Exception {
        for (boolean readOnly : new boolean[]{true, false}) {
            var f = new Fixture();
            int isolation = readOnly ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ;
            assertThatThrownBy(() -> f.transaction(60, readOnly, isolation).execute(status -> f.query()))
                    .isInstanceOf(IllegalStateException.class);
            verify(f.repository, never()).results(anyString(), any(), any());
        }
    }
    @Test void shorterCallerCannotSilentlyUseALongerSqlTransactionBudget() throws Exception {
        var f = new Fixture();
        try (var ignored = CarbonCalculationDeadline.start(Duration.ofSeconds(5)).bind()) {
            assertThatThrownBy(f::query).isInstanceOf(IllegalStateException.class);
            verify(f.repository, never()).results(anyString(), any(), any());
            assertThat(CarbonCalculationDeadline.currentRemaining()).isLessThanOrEqualTo(Duration.ofSeconds(5));
        }
    }
    @Test void shorterJoinedTransactionRetainsItsExistingResourceAndDeadline() throws Exception {
        var f = new Fixture();
        f.transaction(10, true, TransactionDefinition.ISOLATION_REPEATABLE_READ).executeWithoutResult(status -> {
            Object holder = TransactionSynchronizationManager.getResource(f.source);
            assertThat(f.query().points()).hasSize(24);
            assertThat(TransactionSynchronizationManager.getResource(f.source)).isSameAs(holder);
            assertThat(((ConnectionHolder)holder).getTimeToLiveInMillis()).isBetween(1L, 10_000L);
        });
        verify(f.connection).commit();
    }
    @Test void failureRollsBackAndRestoresScopeWithoutRetryingReads() throws Exception {
        var f = new Fixture(); var failure = new org.springframework.dao.QueryTimeoutException("driver timeout");
        doThrow(failure).when(f.repository).results(anyString(), any(), any());
        assertThatThrownBy(f::query).isSameAs(failure);
        verify(f.repository).results(anyString(), any(), any());
        verify(f.connection).rollback(); verify(f.connection).close();
        assertThat(CarbonCalculationDeadline.currentRemaining()).isNull();
        assertThat(TransactionSynchronizationManager.hasResource(f.source)).isFalse();
    }
    @Test void readerDenialStillPrecedesBusinessRepositoryAccess() throws Exception {
        var f = new Fixture(); var denial = new IllegalStateException("reader denied");
        doThrow(denial).when(f.authorization).requireReader(anyLong(), any(), anyString());
        clearInvocations(f.repository);
        assertThatThrownBy(f::query).isSameAs(denial);
        verifyNoInteractions(f.repository);
        assertThat(CarbonCalculationDeadline.currentRemaining()).isNull();
    }

    private static final class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class);
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final DynamicElectricityRepository repository = mock(DynamicElectricityRepository.class);
        final CarbonAuthorization authorization = mock(CarbonAuthorization.class);
        final DynamicElectricityService service;
        Fixture() throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(connection.createStatement()).thenReturn(statement);
            jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
            when(repository.currentReviewedDayDataSource()).thenReturn(source);
            var target = new DynamicElectricityService(authorization, repository, mock(DynamicElectricityInputReader.class),
                    manager, evidence -> { }, new AuditGovernanceProperties(), mock(DynamicElectricityLiveRepository.class));
            var proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            service = (DynamicElectricityService)proxy.getProxy();
        }
        DynamicElectricityContracts.View query() { return service.query(1, List.of("ENERGY_MANAGER"), "B", "DAY", "2026-01-01"); }
        TransactionTemplate transaction(int timeout, boolean readOnly, int isolation) {
            var tx = new TransactionTemplate(manager);tx.setTimeout(timeout);tx.setReadOnly(readOnly);tx.setIsolationLevel(isolation);return tx;
        }
    }
}
