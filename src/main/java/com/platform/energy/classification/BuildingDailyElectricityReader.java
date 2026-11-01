package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import static com.platform.energy.classification.ClassificationContracts.*;

/** 日总量复用分类查询的计量边界、总分表去重和来源裁决，不把分类节点相加。 */
@Service
@RequiredArgsConstructor
public class BuildingDailyElectricityReader {
    private final ClassificationRepository repository;
    private final ClassificationService classification;
    private final EnergyPeriodAuthorization authorization;
    private final ObjectMapper json;

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public QueryView read(long user, Collection<String> roles, String building, LocalDate date) {
        authorization.requireReader(roles);
        authorization.checkBuilding(user, roles, building);
        var zone = ZoneId.of("Asia/Shanghai");
        var start = date.atStartOfDay(zone).toInstant();
        var end = date.plusDays(1).atStartOfDay(zone).toInstant();
        var versions = repository.effective(building, start, end);
        if (versions.size() != 1) throw new BusinessException(409, "DAILY_CLASSIFICATION_REQUIRED", "该日缺少唯一完整有效的计量分类配置");
        var version = versions.getFirst();
        Configuration config;
        try { config = json.readValue(version.json(), Configuration.class); }
        catch (Exception exception) { throw new BusinessException(503, "DAILY_CLASSIFICATION_INVALID", "该日计量分类配置无法读取"); }
        // 无归属筛选时全楼总量与展示方案无关；仅选确定的既有方案进入同一去重路径。
        var scheme = config.schemes().stream().min(Comparator.comparing(Scheme::code))
                .orElseThrow(() -> new BusinessException(409, "DAILY_CLASSIFICATION_REQUIRED", "该日计量分类配置没有统计方案"));
        var units=config.boundaryRules().stream().filter(rule->"ELECTRICITY".equals(rule.energyItemCode()))
                .map(BoundaryRule::unit).distinct().toList();
        if(units.size()!=1 || !"KWH".equalsIgnoreCase(units.getFirst()))
            throw new BusinessException(409,"DAILY_ELECTRICITY_UNIT_REQUIRED","日用电量需要唯一明确的千瓦时计量边界");
        // 单位码按目录规范忽略大小写；分类匹配保留已审核边界的原标识，不更改来源或用量。
        return classification.query(user, roles, building, new QueryRequest(start, end, "DAY", "ELECTRICITY", units.getFirst(), scheme.code(), version.id(), List.of()));
    }
}
