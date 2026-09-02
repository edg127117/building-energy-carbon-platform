package com.platform.energy.period;

import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;

/** 在新封账快照正式可见后，向下游发布同事务、可恢复的版本替代事实。 */
public interface EnergyPeriodSnapshotChangePublisher {
    void published(PeriodSnapshot previous, PeriodSnapshot current);
}
