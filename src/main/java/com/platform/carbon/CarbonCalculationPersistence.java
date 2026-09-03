package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
/** 用独立短事务保存计算开始、完整结果或失败终态，避免持有长事务和行锁。 */
class CarbonCalculationPersistence {
    private final CarbonCalculationRepository repository;
    private final PlatformTransactionManager transactionManager;
    private final CarbonProperties properties;

    void create(CalculationBatch batch) {
        write(() -> {
            repository.insertBatch(batch);
            return null;
        });
    }

    void complete(String batchId, CalculationResult result, int snapshotCount,
                  boolean slow, long durationMs, LocalDateTime completedAt) {
        write(() -> {
            repository.insertItems(batchId, result.items());
            repository.insertFailures(batchId, result.failures());
            repository.insertSummaries(batchId, result.summaries());
            String status = result.complete() ? "COMPLETED_COMPLETE" : "COMPLETED_INCOMPLETE";
            if (repository.completeBatch(batchId, status, snapshotCount,
                    result.items().size() + result.failures().size(), slow, durationMs,
                    completedAt) != 1) {
                throw CarbonErrors.error(409, CarbonErrors.STATUS_CONFLICT,
                        "计算批次状态已变化，拒绝保存结果");
            }
            return null;
        });
    }

    void fail(String batchId, String code, String message,
              LocalDateTime completedAt, long durationMs) {
        write(() -> {
            repository.failBatch(batchId, code, message, completedAt, durationMs);
            return null;
        });
    }

    boolean timeout(String batchId, LocalDateTime now) {
        return write(() -> repository.timeoutBatch(batchId, now,
                properties.getSlowCalculationThreshold().toMillis()) == 1);
    }

    boolean timeoutAborted(String batchId, LocalDateTime now) {
        return write(() -> repository.timeoutAbortedBatch(batchId, now,
                properties.getSlowCalculationThreshold().toMillis()) == 1);
    }

    /** 在创建新批次前原子回收同一计算键已经超过截止时间的运行锁。 */
    int timeoutExpiredScope(String buildingId, PeriodType periodType,
                            java.time.Instant periodStart, java.time.Instant periodEnd,
                            ResultNature resultNature, LocalDateTime now) {
        return write(() -> repository.timeoutExpiredScope(buildingId, periodType, periodStart,
                periodEnd, resultNature, now, properties.getSlowCalculationThreshold().toMillis()));
    }

    /** 每个规则读取各自开启短只读事务，使 MySQL 查询超时继承请求剩余预算。 */
    <T> T read(Supplier<T> operation) {
        return transaction(true, operation);
    }

    void audit(Runnable operation) {
        write(() -> {
            operation.run();
            return null;
        });
    }

    private <T> T write(Supplier<T> operation) {
        return transaction(false, operation);
    }

    private <T> T transaction(boolean readOnly, Supplier<T> operation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        java.time.Duration remaining = CarbonCalculationDeadline.currentRemaining();
        if (remaining != null) {
            java.time.Duration queryBudget = remaining.compareTo(properties.getQueryTimeout()) <= 0
                    ? remaining : properties.getQueryTimeout();
            template.setTimeout(CarbonCalculationDeadline.timeoutSeconds(queryBudget));
        }
        return template.execute(status -> {
            T result = operation.get();
            // 取消 JDBC 等待不一定立即生效；事务返回后再检查截止，禁止迟到工作提交完整结果。
            java.time.Duration budget = CarbonCalculationDeadline.currentRemaining();
            if (budget != null && (budget.isZero() || Thread.currentThread().isInterrupted())) {
                throw CarbonCalculationDeadline.timeout();
            }
            return result;
        });
    }
}
