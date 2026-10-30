package com.platform.carbon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.Collection;
import java.util.List;

import static com.platform.carbon.CarbonModels.*;
import static com.platform.carbon.CarbonErrors.*;

/** 复用正式因子匹配和面积读取做配置检查，不创建活动事实、计算批次或强度结果。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CarbonHistoricalConfigurationReader {
    private final CarbonAuthorization authorization;
    private final CarbonRuleRepository repository;
    private final CarbonCalculationCore core;

    public String factor(long user, Collection<String> roles, String building, String energy,
            String unit, Instant from, Instant to, String timezone) {
        authorization.requireReader(user, roles, building);
        LocalDateTime start = LocalDateTime.ofInstant(from, ZoneId.of(timezone));
        LocalDateTime end = LocalDateTime.ofInstant(to, ZoneId.of(timezone));
        // 零活动量仅用于核对单位及规则契约，不写为读数或核算结果。
        var activity = new ActivitySegment("CONFIGURATION_CHECK", building, PeriodType.MONTH,
                from, to, timezone, energy, BigDecimal.ZERO, unit, null, null,
                ResultNature.FORMAL, null);
        var factor = core.match(activity, repository.findBuildingRegion(building), ResultNature.FORMAL,
                repository.findOverlappingActiveFactors(energy, start, end)).factor();
        if ("GAS_MASS".equals(factor.resultBasis())) {
            var gwp = repository.findActiveGwp(factor.gasCode(), UsageNature.FORMAL, start, end);
            if (gwp.size() != 1) throw error(409, FACTOR_MISSING, "缺少唯一完整覆盖的正式GWP版本");
        }
        return factor.factorVersionId();
    }

    public String area(long user, Collection<String> roles, String building,
            Instant from, Instant to, String timezone) {
        authorization.requireReader(user, roles, building);
        List<DenominatorVersion> values = repository.findActiveDenominators(building,
                DenominatorType.BUILDING_AREA, UsageNature.FORMAL,
                LocalDateTime.ofInstant(from, ZoneId.of(timezone)).toLocalDate(),
                LocalDateTime.ofInstant(to, ZoneId.of(timezone)).toLocalDate());
        if (values.size() != 1) throw error(409, ACTIVITY_INCOMPLETE, "缺少唯一完整覆盖的已生效建筑面积");
        return values.getFirst().denominatorVersionId();
    }
}
