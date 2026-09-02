package com.platform.energy.summary;

import com.platform.carbon.CarbonActivityInputPort;
import com.platform.carbon.CarbonCalculationCore;
import com.platform.carbon.CarbonModels.ActivitySegment;
import com.platform.carbon.CarbonModels.PeriodType;
import com.platform.carbon.CarbonModels.ResultNature;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryGroupView;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.platform.carbon.CarbonErrors.DEPENDENCY_UNAVAILABLE;
import static com.platform.carbon.CarbonErrors.LIMIT_EXCEEDED;
import static com.platform.carbon.CarbonErrors.error;

@Component
@RequiredArgsConstructor
/** 将第七闭环的受控汇总查询适配为碳模块可固定版本的活动段。 */
public class EnergySummaryCarbonActivityAdapter implements CarbonActivityInputPort {
    private static final long SYSTEM_ACTOR_ID = 0L;
    private static final List<String> SYSTEM_ROLES = List.of("PLATFORM_ADMIN");

    private final EnergyBoundarySummaryService summaryService;
    private final EnergyPeriodSnapshotReader snapshotReader;

    @Override
    public List<ActivitySegment> read(String buildingId, PeriodType periodType,
                                      Instant startInclusive, Instant endExclusive, int limit) {
        String upstreamPeriodType = periodType == PeriodType.QUARTER ? "MONTH" : periodType.name();
        var summary = summaryService.query(SYSTEM_ACTOR_ID, SYSTEM_ROLES,
                new SummaryQueryRequest(buildingId, upstreamPeriodType, startInclusive,
                        endExclusive, List.of("ENERGY_ITEM", "PERIOD")));
        List<PeriodSnapshot> snapshots = snapshotReader.listVisibleSnapshots(buildingId,
                upstreamPeriodType, startInclusive, endExclusive, limit + 1);
        if (snapshots.size() > limit) {
            throw error(409, LIMIT_EXCEEDED, "碳活动数据快照超过单次计算上限");
        }
        List<ActivitySegment> result = new ArrayList<>();
        for (SummaryGroupView group : summary.groups()) {
            String itemCode = group.groupKey().get("ENERGY_ITEM");
            Instant[] period = period(group.groupKey().get("PERIOD"));
            Set<String> evidence = Set.copyOf(group.evidenceHashes());
            List<PeriodSnapshot> matched = snapshots.stream()
                    .filter(value -> value.energyItemCode().equals(itemCode))
                    .filter(value -> value.startInclusive().equals(period[0])
                            && value.endExclusive().equals(period[1]))
                    .filter(value -> evidence.contains(value.evidenceHash())).toList();
            if (matched.isEmpty()) {
                throw error(409, DEPENDENCY_UNAVAILABLE,
                        "计量边界汇总与封账快照版本不一致，请重新发起计算");
            }
            String timezone = common(matched.stream().map(PeriodSnapshot::timezoneId).toList(),
                    "封账快照时区不一致");
            String snapshotEvidence = matched.stream().map(PeriodSnapshot::snapshotId)
                    .sorted().reduce((left, right) -> left + "," + right).orElseThrow()
                    + "|relations=" + group.relationVersionIds()
                    + "|summaryPolicies=" + group.summaryPolicyVersionIds()
                    + "|evidence=" + group.evidenceHashes();
            String hash = CarbonCalculationCore.sha256(snapshotEvidence);
            String aggregateSnapshotId = hash.substring(0, 32);
            if (group.grossInboundQuantities().size() != 1) {
                throw error(409, DEPENDENCY_UNAVAILABLE,
                        "同一能源品种和周期必须只有一个权威活动量单位");
            }
            for (var quantity : group.grossInboundQuantities().entrySet()) {
                if (quantity.getValue().signum() < 0) {
                    throw error(409, DEPENDENCY_UNAVAILABLE, "权威活动量不得为负数");
                }
                result.add(new ActivitySegment(aggregateSnapshotId, buildingId, periodType,
                        period[0], period[1], timezone, itemCode, quantity.getValue(),
                        quantity.getKey(), group.lockStatus(), group.resultCompleteness(),
                        "FORMAL".equals(group.resultNature()) ? ResultNature.FORMAL
                                : ResultNature.DEVELOPMENT_SIMULATION, hash));
            }
        }
        if (result.size() > limit) {
            throw error(409, LIMIT_EXCEEDED, "碳活动数据明细超过单次计算上限");
        }
        return List.copyOf(result);
    }

    private static Instant[] period(String value) {
        try {
            String[] parts = value.split("/", 2);
            return new Instant[]{Instant.parse(parts[0]), Instant.parse(parts[1])};
        } catch (RuntimeException exception) {
            throw error(409, DEPENDENCY_UNAVAILABLE, "计量边界汇总周期证据无效");
        }
    }

    private static String common(List<String> values, String message) {
        if (values.isEmpty() || values.stream().distinct().count() != 1) {
            throw error(409, DEPENDENCY_UNAVAILABLE, message);
        }
        return values.getFirst();
    }
}
