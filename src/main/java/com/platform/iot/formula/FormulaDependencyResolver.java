package com.platform.iot.formula;

import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 根据发生质量变化的测点，筛出真正需要重算的指标实例。
 *
 * <p>设备点只影响同建筑、同设备的指标；没有设备归属的环境点可以影响同建筑
 * 的多个设备。语义键直接复用 {@link FormulaInputAssembler} 的构造规则，
 * 避免依赖判断和实际公式输入出现两套口径。</p>
 */
public final class FormulaDependencyResolver {

    private final DataPointConfigProvider pointConfigProvider;

    public FormulaDependencyResolver(DataPointConfigProvider pointConfigProvider) {
        this.pointConfigProvider = Objects.requireNonNull(
                pointConfigProvider, "pointConfigProvider");
    }

    /**
     * 返回受影响指标 ID；未知点位、非计算点或不相关语义键不会触发重算。
     */
    public Set<String> resolve(
            Collection<BizIndicator> activeIndicators,
            Set<String> affectedPointIds,
            Collection<IndicatorFormula> formulas) {
        Objects.requireNonNull(activeIndicators, "activeIndicators");
        Objects.requireNonNull(affectedPointIds, "affectedPointIds");
        Objects.requireNonNull(formulas, "formulas");
        if (affectedPointIds.isEmpty()) {
            return Set.of();
        }

        Map<String, IndicatorFormula> formulasByCode = new LinkedHashMap<>();
        for (IndicatorFormula formula : formulas) {
            formulasByCode.put(formula.indicatorCode(), formula);
        }
        Map<String, PointRuntimeConfig> pointsById = new LinkedHashMap<>();
        for (PointRuntimeConfig point : pointConfigProvider.findAll()) {
            if (affectedPointIds.contains(point.pointId())) {
                pointsById.put(point.pointId(), point);
            }
        }

        Set<String> indicatorIds = new LinkedHashSet<>();
        for (String pointId : affectedPointIds) {
            PointRuntimeConfig point = pointsById.get(pointId);
            if (point == null || point.isForCalc() != 1) {
                continue;
            }
            String semanticKey = FormulaInputAssembler.semanticKey(point);
            boolean environment = point.equipId() == null;
            for (BizIndicator indicator : activeIndicators) {
                IndicatorFormula formula = formulasByCode.get(indicator.getIndicatorCode());
                if (formula == null
                        || !Objects.equals(point.buildingId(), indicator.getBuildingId())
                        || (!environment
                        && !Objects.equals(point.equipId(), indicator.getEquipId()))
                        || !formula.requiredInputKeys().contains(semanticKey)) {
                    continue;
                }
                indicatorIds.add(indicator.getIndicatorId());
            }
        }
        return Set.copyOf(indicatorIds);
    }
}
