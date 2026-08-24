package com.platform.iot.qualityusage;

import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;

import java.util.Set;

/** 成功切换不可变运行快照后发布的有界恢复入口。 */
public record QualityUsageRuntimeRefreshedEvent(
        long previousRevision,
        long currentRevision,
        Set<PolicyKey> affectedPolicies,
        Long earliestAffectedMinute) {
    public QualityUsageRuntimeRefreshedEvent {
        affectedPolicies = Set.copyOf(affectedPolicies);
    }
}
