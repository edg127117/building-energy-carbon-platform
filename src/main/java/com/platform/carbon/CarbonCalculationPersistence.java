package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
/** 用独立短事务保存计算开始、完整结果或失败终态，避免持有长事务和行锁。 */
class CarbonCalculationPersistence {
    private final CarbonCalculationRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    void create(CalculationBatch batch) {
        repository.insertBatch(batch);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    void complete(String batchId, CalculationResult result, int snapshotCount,
                  boolean slow, long durationMs, LocalDateTime completedAt) {
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
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(String batchId, String code, String message,
              LocalDateTime completedAt, long durationMs) {
        repository.failBatch(batchId, code, message, completedAt, durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean timeout(String batchId, LocalDateTime now) {
        return repository.timeoutBatch(batchId, now) == 1;
    }
}
