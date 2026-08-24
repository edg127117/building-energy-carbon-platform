package com.platform.iot.qualityusage;

import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeSnapshot;

import java.util.Set;

/** MySQL 质量使用配置的批量读取边界。 */
public interface QualityUsagePolicyRepository {
    long currentRevision();

    RuntimeSnapshot loadRuntimeSnapshot(long recoveryFromInclusiveMs);

    RuntimeSnapshot loadRange(
            Set<String> pointIds,
            String scenarioCode,
            long fromInclusive,
            long toExclusive);

    default Set<PolicyKey> affectedPolicies(long fromExclusiveRevision, long toInclusiveRevision) {
        return Set.of();
    }

    /** 返回修订区间内最早的策略生效分钟；场景级变化没有版本分钟时返回 {@code null}。 */
    default Long earliestAffectedMinute(long fromExclusiveRevision, long toInclusiveRevision) {
        return null;
    }
}
