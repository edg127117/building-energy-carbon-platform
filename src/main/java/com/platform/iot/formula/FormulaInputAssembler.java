package com.platform.iot.formula;

import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.temporal.model.RawMinuteAggregate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 把一个已冻结分钟的测点聚合组装成单个指标的公式输入。
 *
 * <p>该层负责建筑、设备和分钟范围隔离：设备测点只能进入自身指标，建筑级
 * 环境测点可以供同建筑设备使用。公式只接收标准语义键，不直接依赖 MQTT
 * 外部别名；如果同一语义出现多个测点则拒绝计算，避免随机选值。</p>
 */
public final class FormulaInputAssembler {

    /**
     * 为指定指标提取同建筑、同设备、同分钟且允许参与计算的输入。
     *
     * @param indicator MySQL 中启用的指标实例
     * @param requestedMinute 来源数据的自然分钟起点，Unix 毫秒
     * @param aggregates TDengine 已冻结的分钟聚合
     * @return 以组件/后缀或环境族/后缀索引的不可变输入
     */
    public FormulaInputs assemble(
            BizIndicator indicator,
            long requestedMinute,
            Collection<RawMinuteAggregate> aggregates) {
        Objects.requireNonNull(indicator, "indicator");
        Objects.requireNonNull(aggregates, "aggregates");
        String buildingId = Objects.requireNonNull(
                indicator.getBuildingId(), "indicator.buildingId");

        Map<String, List<FormulaCalculation.Input>> inputsByKey = new TreeMap<>();
        for (RawMinuteAggregate aggregate : aggregates) {
            Objects.requireNonNull(aggregate, "aggregate");
            if (!matchesCommonScope(aggregate, buildingId, requestedMinute)) {
                continue;
            }

            boolean environment = aggregate.equipId() == null;
            if (!environment && !Objects.equals(aggregate.equipId(), indicator.getEquipId())) {
                continue;
            }

            // 环境点没有设备归属，使用族编码；设备点使用组件编码，防止 GW/PPE 串设备。
            String key = semanticKey(aggregate, environment);
            FormulaCalculation.Input input = new FormulaCalculation.Input(
                    key,
                    aggregate.pointId(),
                    aggregate.pointCode(),
                    aggregate.averageValue(),
                    null,
                    aggregate.dataQuality());
            inputsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(input);
        }

        inputsByKey.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .findFirst()
                .ifPresent(entry -> {
                    throw new IllegalArgumentException(
                            "Duplicate formula input key: " + entry.getKey());
                });

        return new FormulaInputs(inputsByKey.values().stream()
                .map(inputs -> inputs.get(0))
                .toList());
    }

    private boolean matchesCommonScope(
            RawMinuteAggregate aggregate, String buildingId, long requestedMinute) {
        return aggregate.isForCalc() == 1
                && aggregate.minuteStart() == requestedMinute
                && buildingId.equals(aggregate.buildingId());
    }

    private String semanticKey(RawMinuteAggregate aggregate, boolean environment) {
        String prefix = environment ? aggregate.familyCode() : aggregate.componentCode();
        if (prefix == null || prefix.isBlank()
                || aggregate.suffixCode() == null || aggregate.suffixCode().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing formula input key metadata for point: " + aggregate.pointId());
        }
        return prefix + "/" + aggregate.suffixCode();
    }
}
