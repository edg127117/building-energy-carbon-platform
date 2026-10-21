package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.platform.energy.classification.ClassificationCore.SourceSlice;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.summary.EnergyBoundarySummaryService;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.platform.carbon.CarbonErrors.*;
import static com.platform.carbon.CarbonProjectContracts.*;

/** 用指定草稿与真实月封账做只读排除预检；不计算碳值，不发布配置或改写历史。 */
@Service
public class CarbonProjectImpactService {
    private final CarbonProjectService projects;
    private final CarbonProjectCore core;
    private final ClassificationService classifications;
    private final EnergyBoundarySummaryService summaries;
    private final EnergyPeriodSnapshotReader snapshots;

    public CarbonProjectImpactService(CarbonProjectService projects, CarbonProjectCore core,
                                     ClassificationService classifications, EnergyBoundarySummaryService summaries,
                                     EnergyPeriodSnapshotReader snapshots) {
        this.projects = projects;
        this.core = core;
        this.classifications = classifications;
        this.summaries = summaries;
        this.snapshots = snapshots;
    }

    public record Line(String energyItemCode, String unit, String status,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossQuantity,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal includedQuantity,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal excludedQuantity,
                       List<SourceSlice> excludedSources) { }
    public record Impact(String buildingId, String versionId, long revision, Instant startInclusive,
                         Instant endExclusive, boolean ready, String calculationStatus,
                         List<Line> lines, List<Issue> issues) { }

    public Impact preview(long user, Collection<String> roles, String building, String versionId,
                          Instant from, Instant to) {
        // 先核对建筑授权，再读取任何封账信息；月窗口必须与上游真实封账边界一致。
        Version version = projects.versions(user, roles, building).stream()
                .filter(v -> v.versionId().equals(versionId)).findFirst()
                .orElseThrow(() -> error(404, NOT_FOUND, "项目版本不存在"));
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw error(400, VALIDATION_FAILED, "排除预览只接受一个完整封账月份，最长31天");
        }
        core.validate(version.configuration());
        var summary = summaries.query(user, roles,
                new SummaryQueryRequest(building, "MONTH", from, to, List.of("ENERGY_ITEM", "PERIOD")));
        var sources = snapshots.listVisibleSnapshots(building, "MONTH", from, to, 5001);
        if (sources.size() > 5000) throw error(409, LIMIT_EXCEEDED, "预览来源超过5000条上限");
        if (summary.sourceSnapshotCount() != sources.size())
            throw error(409, DEPENDENCY_UNAVAILABLE, "汇总与封账快照版本不一致，请重新预览");

        List<Line> lines = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Set<String> encountered = new HashSet<>();
        for (var group : summary.groups()) {
            String energy = group.groupKey().get("ENERGY_ITEM");
            if (!encountered.add(energy) || !Objects.equals(group.groupKey().get("PERIOD"), from + "/" + to))
                throw error(409, ACTIVITY_INCOMPLETE, "预览期间必须对应一个完整封账月份，不能跨期或摊分");
            if (version.configuration().energies().stream().noneMatch(e -> energy.equals(e.energyItemCode()) && "ENABLED".equals(e.capability()))) {
                issues.add(new Issue("ENERGY_NOT_ENABLED", energy, "实际封账能源未在此项目版本启用"));
                lines.add(new Line(energy, null, "UNCONFIGURED", null, null, null, List.of()));
                continue;
            }
            if (group.grossInboundQuantities().size() != 1)
                throw error(409, ACTIVITY_INCOMPLETE, "同一能源必须使用单一权威活动量单位");
            var quantity = group.grossInboundQuantities().entrySet().iterator().next();
            var composition = sources.stream().filter(s -> energy.equals(s.energyItemCode())
                    && quantity.getKey().equals(s.nativeUnitCode()) && from.equals(s.startInclusive())
                    && to.equals(s.endExclusive())).toList();
            Set<String> hashes = new HashSet<>();
            composition.forEach(s -> hashes.add(s.evidenceHash()));
            if (group.evidenceHashes().isEmpty() || !hashes.containsAll(group.evidenceHashes()))
                throw error(409, DEPENDENCY_UNAVAILABLE, "权威汇总证据未包含于本次封账来源");
            try {
                if(!"FORMAL".equals(group.resultNature())||!Set.of("LOCKED_COMPLETE","LOCKED_NATIVE_COMPLETE","MIXED").contains(group.lockStatus())
                        ||!Set.of("COMPLETE","COMPLETE_NATIVE_ONLY").contains(group.resultCompleteness()))
                    throw error(409,ACTIVITY_INCOMPLETE,"权威汇总尚非正式完整封账，不能确认碳排除量");
                var projected = classifications.projectSealed(user, roles, building,
                        version.configuration().classificationVersionId(), from, to, energy, quantity.getKey(), composition);
                if (projected.total().compareTo(quantity.getValue()) != 0)
                    throw error(409, ACTIVITY_INCOMPLETE, "计量分解与权威总量不一致");
                var applied = core.apply(version.configuration(), energy, projected.slices());
                lines.add(new Line(energy, quantity.getKey(), "VERIFIED", quantity.getValue(),
                        applied.included(), applied.excluded(), applied.excludedSlices()));
            } catch (BusinessException exception) {
                if (exception.getCode() == 401 || exception.getCode() == 403) throw exception;
                issues.add(new Issue("SOURCE_UNVERIFIED", energy, exception.getMessage()));
                lines.add(new Line(energy, quantity.getKey(), "UNVERIFIED", quantity.getValue(), null, null, List.of()));
            }
        }
        for (Energy energy : version.configuration().energies()) {
            if ("RESERVED".equals(energy.capability())) {
                lines.add(new Line(energy.energyItemCode(), null, "RESERVED", null, null, null, List.of()));
            } else if (!encountered.contains(energy.energyItemCode())) {
                issues.add(new Issue("SEALED_SOURCE_REQUIRED", energy.energyItemCode(), "该能源缺少指定月份的正式封账来源"));
                lines.add(new Line(energy.energyItemCode(), null, "MISSING", null, null, null, List.of()));
            }
        }
        if (version.configuration().energies().isEmpty()) issues.add(new Issue("ENERGY_REQUIRED", "energies", "尚未选择项目能源"));
        else if(version.configuration().energies().stream().noneMatch(e -> "ENABLED".equals(e.capability())))
            issues.add(new Issue("NO_ENABLED_ENERGY", "energies", "当前只有预留选项，没有已启用核算能源"));
        return new Impact(building, versionId, version.revision(), from, to, issues.isEmpty(),
                "NOT_CALCULATED", List.copyOf(lines), List.copyOf(issues));
    }
}
