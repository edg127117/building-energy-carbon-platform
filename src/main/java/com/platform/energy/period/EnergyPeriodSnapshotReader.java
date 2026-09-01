package com.platform.energy.period;

import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;

import java.time.Instant;
import java.util.List;

/** 向汇总模块提供可见封账快照，隐藏周期结果索引的表结构和批次可见性条件。 */
public interface EnergyPeriodSnapshotReader {
    List<PeriodSnapshot> listVisibleSnapshots(
            String buildingId, String periodType, Instant startInclusive,
            Instant endExclusive, int limit);
}
