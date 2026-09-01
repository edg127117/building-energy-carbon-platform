package com.platform.energy.aggregation;

import com.platform.energy.aggregation.EnergyAggregationModels.AggregationInput;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationQuery;

/**
 * 为聚合核心固定一次计算所需的活动事实和全部版本证据。
 *
 * <p>生产实现负责组合活动数据、测点绑定、有效计量分配、计量事件、审核修正和质量策略；
 * 聚合核心不得绕过该端口访问上游数据库或 Mapper。</p>
 */
public interface EnergyAggregationInputPort {
    AggregationInput load(AggregationQuery query);
}
