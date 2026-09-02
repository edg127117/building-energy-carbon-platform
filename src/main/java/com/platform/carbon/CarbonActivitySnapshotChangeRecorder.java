package com.platform.carbon;

import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodSnapshotChangePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
@RequiredArgsConstructor
/** 将上游已发布的快照替代事实持久化为幂等碳影响分析输入。 */
class CarbonActivitySnapshotChangeRecorder implements EnergyPeriodSnapshotChangePublisher {
    private final CarbonRuleRepository repository;

    @Override
    public void published(PeriodSnapshot previous, PeriodSnapshot current) {
        if (previous == null || current == null
                || previous.snapshotId().equals(current.snapshotId())) return;
        String raw = "ACTIVITY_SNAPSHOT|" + previous.snapshotId() + '|'
                + current.snapshotId() + '|' + current.buildingId();
        LocalDateTime now = LocalDateTime.now();
        try {
            repository.insertDependencyChange(id(), "ACTIVITY_SNAPSHOT", "ENERGY_PERIOD_SNAPSHOT",
                    current.projectionId(), "封账活动快照版本被有效替代",
                    previous.snapshotId(), current.snapshotId(),
                    CarbonCalculationCore.sha256(raw), current.buildingId(),
                    null,
                    LocalDateTime.ofInstant(current.startInclusive(), ZoneId.of(current.timezoneId())),
                    LocalDateTime.ofInstant(current.endExclusive(), ZoneId.of(current.timezoneId())),
                    null, now);
        } catch (DuplicateKeyException ignored) {
            // 同一新旧快照关系只产生一个持久化变化，重复发布保持上游重算幂等。
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
